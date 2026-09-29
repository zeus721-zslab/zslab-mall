package com.zslab.mall.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 인증 필터(Track 33 P5·D-235 PR3 K1). 경로 접두사에 맞는 역할 HttpOnly 쿠키 1개로만 인증한다({@link AuthCookies}). Authorization 헤더는
 * 읽지 않는다.
 *
 * <ul>
 *   <li>쿠키 없음·무효·만료·역할 불일치 → 익명으로 체인 통과(보호 경로는 인가 단계에서 401).
 *   <li>유효 → {@link TokenProvider#verify}로 검증 → {@link AuthenticatedUserStateVerifier}로 회원 상태 재확인(Track 84) →
 *       {@link JwtAuthenticationToken}으로 감싸 SecurityContext 저장 후 체인 진행.
 * </ul>
 *
 * <p>401 응답은 {@link org.springframework.security.web.access.ExceptionTranslationFilter}(ETF)가
 * authenticationEntryPoint(SecurityErrorHandler)로 작성한다. 본 필터는 ETF보다 뒤에 위치하며({@link SecurityConfig}가
 * {@code addFilterBefore(AuthorizationFilter.class)}로 배치) 응답을 직접 쓰지 않는다.
 *
 * <p>Spring Bean이 아니라 {@link SecurityConfig}가 직접 생성해 SecurityFilterChain에 등록한다(@Component 시 서블릿
 * 필터로 이중 등록되는 트랩 회피). JWT는 필터가 직접 인증하므로 AuthenticationManager·Provider가 없다.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenProvider tokenProvider;
    private final AuthenticatedUserStateVerifier userStateVerifier;

    public JwtAuthenticationFilter(TokenProvider tokenProvider, AuthenticatedUserStateVerifier userStateVerifier) {
        this.tokenProvider = tokenProvider;
        this.userStateVerifier = userStateVerifier;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        authenticateFromRoleCookie(request);
        filterChain.doFilter(request, response);
    }

    /**
     * 경로 접두사로 고른 역할 쿠키 1개로 인증한다(D-235). 무효·만료·역할 불일치 쿠키는 요청을 끊지 않고 익명으로 둔다 — 쿠키는
     * 브라우저가 공개 경로에도 자동으로 싣기 때문에, 남은 쿠키 하나로 공개 조회까지 401이 되지 않게 한다. 보호 경로는 인가 단계에서 401이 된다.
     */
    private void authenticateFromRoleCookie(HttpServletRequest request) {
        Optional<ActorRole> expectedRole = AuthCookies.roleForPath(request);
        if (expectedRole.isEmpty()) {
            return;
        }
        Optional<String> token = AuthCookies.tokenFor(request, expectedRole.get());
        if (token.isEmpty()) {
            return;
        }
        try {
            TokenPayload payload = tokenProvider.verify(token.get());
            if (payload.role() != expectedRole.get()) {
                log.debug("[Auth] 역할 쿠키의 토큰 역할 불일치 expected={} actual={}", expectedRole.get(), payload.role());
                return;
            }
            userStateVerifier.verify(payload);
            setAuthentication(payload);
        } catch (AuthenticationException exception) {
            log.debug("[Auth] 역할 쿠키 무효 role={}: {}", expectedRole.get(), exception.getMessage()); // 토큰 값은 남기지 않는다
        }
    }

    private static void setAuthentication(TokenPayload payload) {
        JwtAuthenticationToken authenticated =
                JwtAuthenticationToken.authenticated(payload.actorId(), payload.role());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
    }
}

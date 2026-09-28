package com.zslab.mall.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 인증 필터(Track 33 P5). Authorization 헤더가 {@code "Bearer <token>"} 형식이면 검증해 SecurityContext에 저장한다.
 * 헤더가 없으면 경로 접두사에 맞는 역할 HttpOnly 쿠키 1개로 인증한다({@link AuthCookies}·D-235). 헤더가 있으면 그 값이 무효여도 쿠키를 읽지 않는다.
 *
 * <ul>
 *   <li>{@code "Bearer "} 프리픽스 없음 → 아무것도 하지 않고 체인 통과(익명·permitAll 경로 무영향).
 *   <li>형식 정상 → {@link TokenProvider#verify}로 검증 → {@link AuthenticatedUserStateVerifier}로 회원 상태 재확인(Track 84) →
 *       {@link JwtAuthenticationToken}으로 감싸 SecurityContext 저장 후 체인 진행.
 *   <li>검증 실패({@code BadCredentialsException} 등 AuthenticationException) → 직접 응답을 쓰지 않고 예외를 전파한다.
 * </ul>
 *
 * <p>예외는 {@link org.springframework.security.web.access.ExceptionTranslationFilter}(ETF)가 잡아
 * authenticationEntryPoint(SecurityErrorHandler)로 위임해 401 RFC7807로 응답한다. 따라서 본 필터는 ETF보다 뒤에
 * 위치해야 하며({@link SecurityConfig}가 {@code addFilterBefore(AuthorizationFilter.class)}로 배치), 401 응답 작성
 * 책임을 필터에 두지 않아 exceptionHandling 일원화를 유지한다.
 *
 * <p>Spring Bean이 아니라 {@link SecurityConfig}가 직접 생성해 SecurityFilterChain에 등록한다(@Component 시 서블릿
 * 필터로 이중 등록되는 트랩 회피). JWT는 필터가 직접 인증하므로 AuthenticationManager·Provider가 없다.
 *
 * <p><b>예외 경로</b>: {@code skipMatcher}({@link SecurityConfig#CLAIM_ATTACHMENT_SERVING_MATCHER}·클레임 첨부 인가 서빙·Track 82 D-176)에
 * 걸리는 요청은 본 필터를 건너뛴다. 그 경로는 무효·만료 Bearer가 있어도 401로 요청을 끊지 않고 쿠키 후보까지 독립 평가해야 하므로(후보
 * 하나라도 허가되면 열람·전부 거부면 404) 인증·인가를 {@link com.zslab.mall.attachment.service.ClaimAttachmentAuthorizationService}가 전담한다.
 * permitAll 규칙과 같은 매처 객체를 공유해 범위가 어긋나지 않는다.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenProvider tokenProvider;
    private final AuthenticatedUserStateVerifier userStateVerifier;
    private final RequestMatcher skipMatcher;

    public JwtAuthenticationFilter(TokenProvider tokenProvider, AuthenticatedUserStateVerifier userStateVerifier,
            RequestMatcher skipMatcher) {
        this.tokenProvider = tokenProvider;
        this.userStateVerifier = userStateVerifier;
        this.skipMatcher = skipMatcher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return skipMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            authenticateFromRoleCookie(request);
            filterChain.doFilter(request, response);
            return;
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response); // 비-Bearer 헤더 → 익명(헤더가 있으면 쿠키로 대체 인증하지 않는다·D-235)
            return;
        }

        // verify 실패는 AuthenticationException 전파 → ETF가 authenticationEntryPoint로 401 위임(직접 응답 미작성)
        String token = header.substring(BEARER_PREFIX.length()).trim();
        TokenPayload payload = tokenProvider.verify(token);
        userStateVerifier.verify(payload); // 삭제·탈퇴·자격증명 갱신 이전 토큰 거부(Track 84·BadCredentials → 동일 401 경로)
        setAuthentication(payload);
        filterChain.doFilter(request, response);
    }

    /**
     * 경로 접두사로 고른 역할 쿠키 1개로 인증한다(D-235). 무효·만료·역할 불일치 쿠키는 Bearer와 달리 요청을 끊지 않고 익명으로 둔다 — 쿠키는
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

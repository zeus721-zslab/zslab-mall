package com.zslab.mall.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 인증 필터(Track 33 P5). Authorization 헤더가 {@code "Bearer <token>"} 형식이면 검증해 SecurityContext에 저장한다.
 *
 * <ul>
 *   <li>{@code "Bearer "} 프리픽스 없음(또는 헤더 없음) → 아무것도 하지 않고 체인 통과(익명·permitAll 경로 무영향).
 *   <li>형식 정상 → {@link TokenProvider#verify}로 검증 → {@link JwtAuthenticationToken}으로 감싸 SecurityContext 저장 후 체인 진행.
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
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenProvider tokenProvider;

    public JwtAuthenticationFilter(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response); // 비-Bearer·무헤더 → 익명 흐름 무영향
            return;
        }

        // verify 실패는 AuthenticationException 전파 → ETF가 authenticationEntryPoint로 401 위임(직접 응답 미작성)
        String token = header.substring(BEARER_PREFIX.length()).trim();
        TokenPayload payload = tokenProvider.verify(token);
        JwtAuthenticationToken authenticated =
                JwtAuthenticationToken.authenticated(payload.actorId(), payload.role());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }
}

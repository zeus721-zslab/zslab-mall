package com.zslab.mall.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stub 인증 필터(Track 31 Phase 1). Authorization 헤더가 {@code "Stub <role>:<id>"} 형식이면 파싱해 인증을 시도하고
 * 성공 시 SecurityContext에 저장한다.
 *
 * <ul>
 *   <li>{@code "Stub "} 프리픽스 없음(또는 헤더 없음) → 아무것도 하지 않고 체인 통과(기존 X-*-Id 헤더 흐름 무영향).
 *   <li>형식 정상 → 미인증 토큰 생성 → {@link AuthenticationManager} 위임 → 성공 시 SecurityContext 저장 후 체인 진행.
 *   <li>형식 오류·알 수 없는 role·id 파싱 실패 → 401({@link AuthenticationEntryPoint} 위임·체인 미진행).
 * </ul>
 *
 * <p>Spring Bean이 아니라 {@link SecurityConfig}가 직접 생성해 SecurityFilterChain에 등록한다(@Component 시 서블릿
 * 필터로 이중 등록되는 트랩 회피). SecurityConfig가 {@code @Profile("!prod")}라 prod에선 생성되지 않는다.
 */
public class StubAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(StubAuthenticationFilter.class);
    private static final String STUB_PREFIX = "Stub ";

    private final AuthenticationManager authenticationManager;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public StubAuthenticationFilter(
            AuthenticationManager authenticationManager,
            AuthenticationEntryPoint authenticationEntryPoint) {
        this.authenticationManager = authenticationManager;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(STUB_PREFIX)) {
            filterChain.doFilter(request, response); // 기존 헤더(X-*-Id) 흐름 무영향
            return;
        }

        StubAuthenticationToken unauthenticated;
        try {
            unauthenticated = parse(header);
        } catch (IllegalArgumentException ex) {
            // 형식 오류·알 수 없는 role·id 비정상 → 인증 실패(401)
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response, new BadCredentialsException("잘못된 Stub 인증 헤더", ex));
            return;
        }

        try {
            Authentication authenticated = authenticationManager.authenticate(unauthenticated);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (AuthenticationException ex) {
            SecurityContextHolder.clearContext();
            log.debug("Stub 인증 실패: {}", ex.getMessage());
            authenticationEntryPoint.commence(request, response, ex);
        }
    }

    /** {@code "Stub <role>:<id>"} → 미인증 토큰. 형식 위반 시 {@link IllegalArgumentException}. */
    private StubAuthenticationToken parse(String header) {
        String body = header.substring(STUB_PREFIX.length()).trim();
        int separator = body.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("role:id 구분자(:) 누락");
        }
        StubRole role = StubRole.fromToken(body.substring(0, separator));
        Long actorId = Long.valueOf(body.substring(separator + 1).trim());
        return new StubAuthenticationToken(role, actorId);
    }
}

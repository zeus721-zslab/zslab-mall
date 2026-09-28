package com.zslab.mall.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * 역할별 HttpOnly 액세스 토큰 쿠키(D-235). 역할마다 이름·Path가 하나씩이며, 요청 경로 접두사로 읽을 쿠키 1개를 고른다:
 * {@code /api/v1/admin/**} → 관리자 · {@code /api/v1/seller/**} → 셀러 · 그 외 {@code /api/v1/**} → 구매자. 다른 역할 쿠키는 읽지 않는다.
 *
 * <p>이름은 FE가 JS로 쓰던 옛 쿠키(auth_token·seller_token·admin_token)와 겹치지 않는다 — 전환기에 같은 이름 쿠키가 Path만 달리 동시 전송되면
 * 어느 값으로 판정할지 모호해지기 때문이다. 옛 이름은 옛 첨부 경로({@link RequestTokenCandidates})에서만 읽는다(PR3에서 제거).
 * 경로 판정은 인가 규칙과 같은 {@link PathPatternRequestMatcher}(세그먼트 디코딩)를 써서 쿠키 선택과 hasRole 범위가 어긋나지 않게 한다.
 */
@Component
public class AuthCookies {

    public static final String BUYER_COOKIE = "__Secure-buyer_at";
    public static final String SELLER_COOKIE = "__Secure-seller_at";
    public static final String ADMIN_COOKIE = "__Secure-admin_at";
    static final String BUYER_PATH = "/api/v1";
    static final String SELLER_PATH = "/api/v1/seller";
    static final String ADMIN_PATH = "/api/v1/admin";

    private static final RequestMatcher ADMIN_MATCHER = PathPatternRequestMatcher.pathPattern(ADMIN_PATH + "/**");
    private static final RequestMatcher SELLER_MATCHER = PathPatternRequestMatcher.pathPattern(SELLER_PATH + "/**");
    private static final RequestMatcher BUYER_MATCHER = PathPatternRequestMatcher.pathPattern(BUYER_PATH + "/**");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");
    private static final String SAME_SITE = "Lax";

    private final Duration maxAge;

    public AuthCookies(@Value("${jwt.expiration-ms}") long expirationMs) {
        this.maxAge = Duration.ofMillis(expirationMs);
    }

    /** 로그인 성공 시 역할 쿠키. Max-Age는 토큰 TTL과 같다(쿠키가 토큰보다 오래 남지 않게). */
    public ResponseCookie issue(ActorRole role, String token) {
        return build(role, token, maxAge);
    }

    /** 로그아웃 시 역할 쿠키 만료(Max-Age=0·같은 이름·Path여야 브라우저가 지운다). 서버 측 토큰 무효화는 하지 않는다. */
    public ResponseCookie expire(ActorRole role) {
        return build(role, "", Duration.ZERO);
    }

    /** 요청 경로가 가리키는 역할. {@code /api/v1/**} 밖(웹훅·actuator 등)은 쿠키 인증 대상이 아니다. */
    static Optional<ActorRole> roleForPath(HttpServletRequest request) {
        if (ADMIN_MATCHER.matches(request)) {
            return Optional.of(ActorRole.ADMIN);
        }
        if (SELLER_MATCHER.matches(request)) {
            return Optional.of(ActorRole.SELLER);
        }
        if (BUYER_MATCHER.matches(request)) {
            return Optional.of(ActorRole.BUYER);
        }
        return Optional.empty();
    }

    /** 경로 접두사로 고른 역할 쿠키의 값(빈 값 제외·검증 전). */
    static Optional<String> tokenFor(HttpServletRequest request, ActorRole role) {
        String name = cookieName(role);
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue().trim());
            }
        }
        return Optional.empty();
    }

    /**
     * CSRF 적용 조건(D-235·Q1): unsafe 메서드 + Authorization 헤더 없음 + 경로에 맞는 역할 쿠키 존재. 쿠키가 자격증명으로 쓰일 수 있는 요청만
     * 보호하므로 Bearer 요청·웹훅·익명 요청은 CSRF 대상이 아니다. 판정은 {@link JwtAuthenticationFilter}의 쿠키 선택과 같은 함수를 쓴다.
     */
    static boolean requiresCsrfProtection(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod()) || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return false;
        }
        return roleForPath(request).flatMap(role -> tokenFor(request, role)).isPresent();
    }

    private static ResponseCookie build(ActorRole role, String value, Duration cookieMaxAge) {
        return ResponseCookie.from(cookieName(role), value)
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .path(cookiePath(role))
                .maxAge(cookieMaxAge)
                .build();
    }

    private static String cookieName(ActorRole role) {
        return switch (role) {
            case BUYER -> BUYER_COOKIE;
            case SELLER -> SELLER_COOKIE;
            case ADMIN -> ADMIN_COOKIE;
        };
    }

    private static String cookiePath(ActorRole role) {
        return switch (role) {
            case BUYER -> BUYER_PATH;
            case SELLER -> SELLER_PATH;
            case ADMIN -> ADMIN_PATH;
        };
    }
}

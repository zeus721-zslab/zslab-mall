package com.zslab.mall.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;

/**
 * 클레임 첨부 인가 서빙(Track 82 D-176) 전용 후보 토큰 추출. {@code <img src>}는 Authorization 헤더를 실을 수 없어 FE가 토큰을 보관하는
 * 쿠키(관리자 {@value #ADMIN_TOKEN_COOKIE}·구매자 {@value #AUTH_TOKEN_COOKIE})를 후보로 함께 읽는다. 순서는 Bearer → admin_token →
 * auth_token이며 검증·판정은 호출부가 후보별로 독립 수행한다.
 *
 * <p><b>CSRF 경계</b>: 쿠키 인식은 {@code GET /api/v1/files/claims/**} 한 경로에서만 쓰인다({@link JwtAuthenticationFilter}는 쿠키를 읽지
 * 않으므로 그 외 모든 경로·메서드는 Bearer만 인증된다). 이 클래스를 다른 경로에서 호출하지 말 것.
 */
public final class RequestTokenCandidates {

    /** FE 관리자 세션 쿠키명(frontend layers/admin adminAuth.ts ADMIN_TOKEN_COOKIE와 동일). */
    public static final String ADMIN_TOKEN_COOKIE = "admin_token";
    /** FE 구매자 세션 쿠키명(frontend app/stores/auth.ts와 동일). */
    public static final String AUTH_TOKEN_COOKIE = "auth_token";
    private static final String BEARER_PREFIX = "Bearer ";

    private RequestTokenCandidates() {
    }

    /** Bearer → admin_token → auth_token 순서의 원시 토큰 문자열(빈 값 제외·검증 전). */
    public static List<String> of(HttpServletRequest request) {
        List<String> candidates = new ArrayList<>();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            addIfPresent(candidates, header.substring(BEARER_PREFIX.length()));
        }
        addIfPresent(candidates, cookieValue(request, ADMIN_TOKEN_COOKIE));
        addIfPresent(candidates, cookieValue(request, AUTH_TOKEN_COOKIE));
        return candidates;
    }

    private static void addIfPresent(List<String> candidates, String token) {
        if (token != null && !token.isBlank()) {
            candidates.add(token.trim());
        }
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

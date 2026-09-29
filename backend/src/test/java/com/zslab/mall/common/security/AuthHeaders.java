package com.zslab.mall.common.security;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 테스트 전용 인증 주입기(D-235 PR3 K10). 역할 HttpOnly 쿠키 + XSRF-TOKEN 쿠키 + X-XSRF-TOKEN 헤더를 MockMvc 요청에 싣는다
 * ({@code .with(authHeaders.buyer(id))}). 쿠키는 {@code request.getCookies()}로 읽히므로 Cookie 헤더가 아니라 요청 쿠키로 넣는다
 * (MockMvc는 Cookie 헤더를 getCookies에 반영하지 않는다).
 *
 * <p>실 {@link TokenProvider}로 토큰을 발급하므로 static 유틸이 아니라 빈이다. 쿠키 이름은 역할마다 고정이고, 서버는 경로 접두사에 맞는
 * 쿠키 1개만 읽으므로 다른 역할 경로에 보낸 요청은 익명이 된다({@link AuthCookies#roleForPath}).
 */
@Component
public class AuthHeaders {

    /** CookieCsrfTokenRepository는 쿠키 값과 헤더 값의 일치만 확인하므로 고정 값을 쓴다. */
    public static final String CSRF_TOKEN = "test-csrf-token";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final TokenProvider tokenProvider;

    public AuthHeaders(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    public RequestPostProcessor buyer(long id) {
        return roleCookie(id, ActorRole.BUYER);
    }

    public RequestPostProcessor seller(long id) {
        return roleCookie(id, ActorRole.SELLER);
    }

    public RequestPostProcessor admin(long id) {
        return roleCookie(id, ActorRole.ADMIN);
    }

    /** 인증 없이 CSRF 쿠키+헤더만 싣는다 — 역할 쿠키 없이도 CSRF를 검증하는 로그인 요청용(D-235 PR3 K7). */
    public RequestPostProcessor csrf() {
        return request -> addCookieAndCsrf(request, null);
    }

    private RequestPostProcessor roleCookie(long id, ActorRole role) {
        Cookie credential = new Cookie(cookieName(role), tokenProvider.issue(id, role));
        return request -> addCookieAndCsrf(request, credential);
    }

    /** @param credential 역할 쿠키(없으면 null) */
    private static MockHttpServletRequest addCookieAndCsrf(MockHttpServletRequest request, Cookie credential) {
        List<Cookie> cookies = new ArrayList<>();
        if (request.getCookies() != null) {
            cookies.addAll(Arrays.asList(request.getCookies()));
        }
        if (credential != null) {
            cookies.add(credential);
        }
        if (cookies.stream().noneMatch(cookie -> CSRF_COOKIE.equals(cookie.getName()))) {
            cookies.add(new Cookie(CSRF_COOKIE, CSRF_TOKEN));
        }
        request.setCookies(cookies.toArray(Cookie[]::new));
        if (request.getHeader(CSRF_HEADER) == null) {
            request.addHeader(CSRF_HEADER, CSRF_TOKEN);
        }
        return request;
    }

    private static String cookieName(ActorRole role) {
        return switch (role) {
            case BUYER -> AuthCookies.BUYER_COOKIE;
            case SELLER -> AuthCookies.SELLER_COOKIE;
            case ADMIN -> AuthCookies.ADMIN_COOKIE;
        };
    }
}

package com.zslab.mall.common.security;

import org.springframework.http.HttpHeaders;

/**
 * 테스트 전용 Stub 인증 헤더 빌더(Track 31 Phase 3). {@code Authorization: Stub <role>:<id>} 헤더를 생성해
 * @SpringBootTest MockMvc 요청에 role 게이트(hasRole) 통과용 인증을 주입한다. base class가 아니라 static 유틸이다.
 */
public final class AuthHeaders {

    private AuthHeaders() {}

    public static HttpHeaders buyer(long id) {
        return stub("buyer", id);
    }

    public static HttpHeaders seller(long id) {
        return stub("seller", id);
    }

    public static HttpHeaders admin(long id) {
        return stub("admin", id);
    }

    private static HttpHeaders stub(String role, long id) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Stub " + role + ":" + id);
        return headers;
    }
}

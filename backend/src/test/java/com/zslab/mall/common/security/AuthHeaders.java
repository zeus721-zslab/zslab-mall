package com.zslab.mall.common.security;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 테스트 전용 인증 헤더 빌더(Track 31 Phase 3·Track 33 P6 Bearer 전환). {@code Authorization: Bearer <JWT>} 헤더를
 * 생성해 @SpringBootTest MockMvc 요청에 role 게이트(hasRole) 통과용 인증을 주입한다.
 *
 * <p>실 {@link TokenProvider}로 토큰을 발급하므로 static 유틸이 아니라 빈이다(test source 컴포넌트 스캔 대상·주입은
 * {@code @Autowired AuthHeaders}). 발급 토큰은 {@link JwtAuthenticationFilter}가 검증해 principal=actorId·ROLE_xxx로
 * SecurityContext를 채운다.
 */
@Component
public class AuthHeaders {

    private final TokenProvider tokenProvider;

    public AuthHeaders(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    public HttpHeaders buyer(long id) {
        return bearer(id, ActorRole.BUYER);
    }

    public HttpHeaders seller(long id) {
        return bearer(id, ActorRole.SELLER);
    }

    public HttpHeaders admin(long id) {
        return bearer(id, ActorRole.ADMIN);
    }

    private HttpHeaders bearer(long id, ActorRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.issue(id, role));
        return headers;
    }
}

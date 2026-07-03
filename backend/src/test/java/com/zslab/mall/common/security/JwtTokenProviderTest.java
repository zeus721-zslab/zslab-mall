package com.zslab.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * {@link JwtTokenProvider} 단위 테스트(Track 33). 실 Spring 컨텍스트·DB 없이 시크릿·만료를 직접 주입해 issue→verify
 * 왕복 일치와, 만료·변조 서명의 검증 실패({@link BadCredentialsException})를 확인한다.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-jwt-secret-at-least-32-bytes-xxxxxxxx";
    private static final long ONE_HOUR_MS = 3_600_000L;

    @Test
    @DisplayName("issue→verify 왕복: actorId·role 일치")
    void issueThenVerify_roundTrips() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, ONE_HOUR_MS);

        String token = provider.issue(100L, ActorRole.BUYER);
        TokenPayload payload = provider.verify(token);

        assertThat(payload.actorId()).isEqualTo(100L);
        assertThat(payload.role()).isEqualTo(ActorRole.BUYER);
    }

    @Test
    @DisplayName("만료 토큰 verify → BadCredentialsException")
    void expiredToken_throws() {
        // 음수 만료 → exp가 발급 시점보다 과거라 즉시 만료
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, -60_000L);

        String token = provider.issue(5L, ActorRole.SELLER);

        assertThatThrownBy(() -> provider.verify(token))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("다른 시크릿 서명(변조) verify → BadCredentialsException")
    void tamperedSignature_throws() {
        JwtTokenProvider issuer = new JwtTokenProvider(SECRET, ONE_HOUR_MS);
        JwtTokenProvider verifier =
                new JwtTokenProvider("another-unit-test-secret-32-bytes-min-yyyyyyyy", ONE_HOUR_MS);

        String token = issuer.issue(3L, ActorRole.ADMIN);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(BadCredentialsException.class);
    }
}

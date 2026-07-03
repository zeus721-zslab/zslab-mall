package com.zslab.mall.common.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Stub 인증 provider(Track 31 Phase 1). 미인증 {@link StubAuthenticationToken}을 credential 검증 없이 인증 완료
 * 토큰으로 승격한다(id·role 신뢰). 실제 credential 검증은 Phase 2/3 실 인증 도입 시 대체된다.
 *
 * <p>{@code @Profile("!prod")} — prod 프로파일엔 미등록(운영은 Stub 인증을 쓰지 않는다).
 */
@Component
@Profile("!prod")
public class StubAuthenticationProvider implements AuthenticationProvider {

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        StubAuthenticationToken token = (StubAuthenticationToken) authentication;
        return StubAuthenticationToken.authenticated(token.getRole(), (Long) token.getPrincipal());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return StubAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

package com.zslab.mall.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * SecurityContext 기반 Buyer 액터 식별자 해석(Track 31 Phase 3 신설). Stub 인증 필터가 채운 principal(actorId·BIGINT)을
 * 반환한다. Seller/Admin resolver는 레거시명 {@code Header*}를 유지하나(rename 이연) 본 구현체는 신설이므로 실제 동작을
 * 반영한 {@code SecurityContext*} 명을 쓴다.
 *
 * <p>인증된 액터가 없으면 401({@link com.zslab.mall.common.exception.UnauthenticatedException}). request 인자는 미사용이다.
 */
@Component
public class SecurityContextBuyerActorResolver implements BuyerActorResolver {

    @Override
    public Long resolve(HttpServletRequest request) {
        return SecurityContextActorSupport.requireActorId();
    }
}

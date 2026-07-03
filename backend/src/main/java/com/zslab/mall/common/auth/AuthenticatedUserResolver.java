package com.zslab.mall.common.auth;

import org.springframework.stereotype.Component;

/**
 * role 무관 인증 주체 userId 추출(Track 34). 자기 리소스 접근(비밀번호 변경 등) 전용 seam.
 *
 * <p>Buyer/Seller/Admin resolver가 이름만 role별이고 동작은 동일하게 principal(actorId)을 추출하는 것과 달리,
 * 본 resolver는 role 의미를 배제하고 "인증된 주체 자신"의 식별자만 반환한다. {@link SecurityContextActorSupport}에 위임한다.
 */
@Component
public class AuthenticatedUserResolver {

    /**
     * 인증된 주체의 userId(BIGINT)를 반환한다.
     *
     * @throws com.zslab.mall.common.exception.UnauthenticatedException 인증된 주체가 없는 경우(401)
     */
    public Long requireUserId() {
        return SecurityContextActorSupport.requireActorId();
    }
}

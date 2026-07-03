package com.zslab.mall.common.security;

import org.springframework.stereotype.Component;

/**
 * {@link RoleAuthorization} 초기 Stub 구현 — 항상 통과. RBAC 트랙 교체 대상.
 *
 * <p>현재 role 매핑 데이터(user_role·seller_user 실조회)는 미배선(D-116 §8)이라, 자격 검증 계약(seam)만 지금 확정하고
 * 실 조회는 RBAC 트랙에서 이 구현체를 교체 1점으로 대응한다. 지금은 인증(비번)만으로 role을 신뢰한다.
 */
@Component
public class StubRoleAuthorization implements RoleAuthorization {

    @Override
    public boolean isAuthorized(Long actorId, ActorRole role) {
        return true;
    }
}

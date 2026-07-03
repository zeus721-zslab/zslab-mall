package com.zslab.mall.common.security;

/**
 * 로그인 시 요청 actor가 해당 role을 사용할 자격이 있는지 검증. (Track 33)
 * 초기 구현은 Stub(항상 통과). RBAC 트랙에서 user_role/seller_user 실조회로 교체 1점.
 * ※ 클라이언트가 보낸 role을 무검증 신뢰하지 않기 위한 계약. 계약은 지금부터 유지.
 */
public interface RoleAuthorization {
    /** actorId가 role 자격이 있으면 true. 없으면 false. */
    boolean isAuthorized(Long actorId, ActorRole role);
}

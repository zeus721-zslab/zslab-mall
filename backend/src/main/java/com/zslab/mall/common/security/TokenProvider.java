package com.zslab.mall.common.security;

/**
 * 인증 토큰 발급·검증 계약. (Track 33)
 * JWT 구현 기본. 추후 Redis 세션 전환 시 구현체 교체 1점으로 대응.
 */
public interface TokenProvider {

    /** 데모 표식 없는 토큰(공개 데모 로그인 외 전부). */
    default String issue(Long actorId, ActorRole role) {
        return issue(actorId, role, false);
    }

    /** @param publicDemo 공개 관리자 데모 세션 표식(최종 점검 K1 · {@link PublicDemoSessionGuard}가 읽는다) */
    String issue(Long actorId, ActorRole role, boolean publicDemo);

    TokenPayload verify(String token);
}

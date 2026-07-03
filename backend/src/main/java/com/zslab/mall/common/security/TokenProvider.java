package com.zslab.mall.common.security;

/**
 * 인증 토큰 발급·검증 계약. (Track 33)
 * JWT 구현 기본. 추후 Redis 세션 전환 시 구현체 교체 1점으로 대응.
 */
public interface TokenProvider {
    String issue(Long actorId, ActorRole role);
    TokenPayload verify(String token);
}

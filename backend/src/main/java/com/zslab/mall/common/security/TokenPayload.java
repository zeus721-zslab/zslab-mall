package com.zslab.mall.common.security;

/** 토큰 검증 결과. 프레임워크 무관 순수 값. (Track 33) */
public record TokenPayload(Long actorId, ActorRole role) {}

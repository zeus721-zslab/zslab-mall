package com.zslab.mall.common.security;

import java.time.Instant;

/**
 * 토큰 검증 결과. 프레임워크 무관 순수 값. issuedAt은 iat 클레임(초 정밀도·Track 84 자격증명 갱신 비교용). publicDemo는 공개 관리자 데모
 * 세션 표식(최종 점검 K1). (Track 33)
 */
public record TokenPayload(Long actorId, ActorRole role, Instant issuedAt, boolean publicDemo) {}

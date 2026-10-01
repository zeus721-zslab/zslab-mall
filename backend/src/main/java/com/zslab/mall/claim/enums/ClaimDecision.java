package com.zslab.mall.claim.enums;

/**
 * 운영자의 클레임 처리 결정(D-250 제안 기록). DB {@code claim_suggestion_record.decision} ENUM과 1:1.
 * API 응답·요청에 실리지 않아 FE 상수는 두지 않는다.
 */
public enum ClaimDecision {
    APPROVE,
    REJECT
}

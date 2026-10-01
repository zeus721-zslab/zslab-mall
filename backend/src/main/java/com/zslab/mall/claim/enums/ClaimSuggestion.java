package com.zslab.mall.claim.enums;

/**
 * 클레임 처리 제안(D-250 · 운영 인박스 P2). 거부 제안은 두지 않는다 — 돈·제재 판단은 사람이 확정한다(D-220).
 * DB {@code claim_suggestion_record.suggestion} ENUM · FE {@code CLAIM_SUGGESTIONS}와 1:1.
 */
public enum ClaimSuggestion {
    /** 승인 제안. 일괄 승인 대상이다. */
    APPROVE,
    /** 운영자 검토 필요. */
    REVIEW
}

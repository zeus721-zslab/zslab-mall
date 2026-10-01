package com.zslab.mall.claim.enums;

/**
 * 클레임 제안 규칙 키(D-250 §2 규칙표 R0~R5). 규칙마다 제안과 근거 문구가 고정이다.
 * DB {@code claim_suggestion_record.rule_key} ENUM · FE {@code CLAIM_SUGGESTION_RULES}와 1:1.
 */
public enum ClaimSuggestionRule {
    /** R0 그 외. */
    NO_MATCH(ClaimSuggestion.REVIEW, "규칙 해당 없음"),
    /** R1 교환 옵션 판매 중 아님 또는 가용 재고 &lt; 수량. */
    EXCHANGE_STOCK_SHORT(ClaimSuggestion.REVIEW, "교환 옵션 재고 부족"),
    /** R2 취소 · 요청 시 품목 PAID·PREPARING. */
    UNSHIPPED_CANCEL(ClaimSuggestion.APPROVE, "미출고 취소 요청"),
    /** R3 반품·교환 · 상품불량·오배송 · 첨부 있음. */
    DEFECT_WITH_EVIDENCE(ClaimSuggestion.APPROVE, "증빙 첨부"),
    /** R4 반품·교환 · 상품불량·오배송 · 첨부 없음. */
    DEFECT_WITHOUT_EVIDENCE(ClaimSuggestion.REVIEW, "증빙 없음"),
    /** R5 반품·교환 · 단순 변심(요청 시 7일 기한 검증 통과). */
    CHANGE_OF_MIND(ClaimSuggestion.APPROVE, "기한 내 단순 변심");

    private final ClaimSuggestion suggestion;
    private final String reason;

    ClaimSuggestionRule(ClaimSuggestion suggestion, String reason) {
        this.suggestion = suggestion;
        this.reason = reason;
    }

    public ClaimSuggestion suggestion() {
        return suggestion;
    }

    public String reason() {
        return reason;
    }
}

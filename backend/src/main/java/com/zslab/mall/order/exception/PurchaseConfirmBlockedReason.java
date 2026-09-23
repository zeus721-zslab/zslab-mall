package com.zslab.mall.order.exception;

/**
 * 구매확정 차단 사유(Track 104-4). 사유마다 다른 ProblemDetail.code로 응답한다 — 구매자 화면은 detail 문구를 그대로 보여 주므로 사유를
 * detail이 아니라 code로 가른다({@code OrderNotPayableReason}과 다른 이유).
 */
public enum PurchaseConfirmBlockedReason {
    /** 품목 순수령액(total_price − 기환불액) 0 이하. */
    NET_AMOUNT_NOT_POSITIVE,
    /** 주문에 미해결(OPEN) 불일치 존재. */
    RECONCILIATION_OPEN
}

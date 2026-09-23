package com.zslab.mall.reconciliation.enums;

/**
 * 주문·결제 불일치 유형(reconciliation_issue.issue_type DDL ENUM 1:1·V35·4층위 잠금·Track 104-2 D-216).
 * FE 상수는 {@code frontend/layers/admin/app/lib/constants/reconciliation.ts}와 1:1이다(값을 바꾸면 넷을 함께 고친다).
 */
public enum ReconciliationIssueType {

    /** PG 결제 성공 통지가 내부 규칙과 충돌(같은 주문 두 번째 결제·종결된 결제·종료 주문 늦은 승인·결제 불가 품목 — 세부는 detail.reason). */
    PG_PAYMENT_SUCCESS_CONFLICT,
    /** 결제 완료(PAID) 결제에 PG 취소 통지(환불 흐름 우회 금지·D-198). */
    PG_PAYMENT_CANCEL_ON_PAID,
    /** PG 결제 거래 id가 다른 결제 행에 이미 기록됨(PAY-3b). */
    PG_TID_CONFLICT,
    /** 매칭되는 결제·환불 행이 없는 PG 통지. */
    PG_UNMATCHED_CALLBACK,
    /** PG 환불 완료가 결제액을 초과(PAY-1 사후). */
    PG_REFUND_EXCEEDS_PAYMENT,
    /** 실패 처리된 환불에 PG 환불 성공 통지(RFN-2). */
    PG_REFUND_SUCCESS_ON_FAILED,
    /** 환불·클레임 없이 결제만 취소됨. */
    PAYMENT_CANCELLED_WITHOUT_REFUND,
    /** 전액 환불 완료인데 결제가 취소 상태가 아님. */
    FULL_REFUND_PAYMENT_NOT_CANCELLED,
    /** 구매확정 품목이 있는 주문의 결제가 전액 환불됨. */
    FULL_REFUND_WITH_CONFIRMED_ITEM,
    /** 교환 클레임·거부된 클레임에 환불 완료. */
    REFUND_ON_INVALID_CLAIM,
    /** 클레임·배송 상태와 품목 상태가 어긋남(세부는 detail.reason = 점검 패턴 이름). */
    ITEM_STATE_DRIFT
}

package com.zslab.mall.reconciliation.service;

/**
 * 불일치가 가리키는 대상 식별 정보(전부 nullable·논리 참조·Track 104-2 D-216). 발생 지점마다 손에 있는 값이 달라 유형군별 생성 메서드를 둔다.
 */
public record ReconciliationIssueRefs(
        Long orderId,
        Long paymentId,
        Long refundId,
        Long claimId,
        Long deliveryId,
        String pgTid,
        String pgRefundId) {

    /** 결제 대상(PG 결제 통지 충돌·결제 점검). */
    public static ReconciliationIssueRefs ofPayment(Long orderId, Long paymentId, String pgTid) {
        return new ReconciliationIssueRefs(orderId, paymentId, null, null, null, pgTid, null);
    }

    /** 환불 대상(PG 환불 통지 충돌·환불 완료 시점 기록). */
    public static ReconciliationIssueRefs ofRefund(Long orderId, Long paymentId, Long refundId, Long claimId, String pgRefundId) {
        return new ReconciliationIssueRefs(orderId, paymentId, refundId, claimId, null, null, pgRefundId);
    }

    /** 클레임 대상(품목 상태 어긋남). */
    public static ReconciliationIssueRefs ofClaim(Long orderId, Long claimId) {
        return new ReconciliationIssueRefs(orderId, null, null, claimId, null, null, null);
    }

    /** 배송 대상(품목 상태 어긋남). */
    public static ReconciliationIssueRefs ofDelivery(Long orderId, Long deliveryId) {
        return new ReconciliationIssueRefs(orderId, null, null, null, deliveryId, null, null);
    }

    /** 매칭 행 없는 PG 통지 — 주문·결제·환불 id가 없고 통지 원문 값만 있다. */
    public static ReconciliationIssueRefs unmatched(String pgTid, String pgRefundId) {
        return new ReconciliationIssueRefs(null, null, null, null, null, pgTid, pgRefundId);
    }
}

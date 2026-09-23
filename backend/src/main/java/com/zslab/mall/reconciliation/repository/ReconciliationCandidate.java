package com.zslab.mall.reconciliation.repository;

/**
 * 점검 스케줄러 후보 1행(Track 104-2 D-216·네이티브 조회 projection). 모든 후보 조회가 같은 별칭 전체를 SELECT하며 해당 없는 값은 NULL이다.
 * {@link #getTargetId()}는 중복 키에 붙는 대상 id(결제·클레임·배송 중 패턴이 정한 것)다.
 */
public interface ReconciliationCandidate {

    Long getTargetId();

    Long getOrderId();

    Long getPaymentId();

    Long getClaimId();

    Long getDeliveryId();

    Long getOrderItemId();

    String getPgTid();

    String getPaymentStatus();

    Long getPaymentAmount();

    Long getRefundedAmount();

    String getItemStatus();

    String getClaimType();

    String getClaimStatus();

    String getDeliveryStatus();
}

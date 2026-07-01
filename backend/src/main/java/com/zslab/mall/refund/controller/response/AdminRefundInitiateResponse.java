package com.zslab.mall.refund.controller.response;

import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;

/**
 * 운영자 수동 환불 개시 응답(Track 22·D-106 §5). 식별자는 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>정상 개시 시 status=PENDING·pgRefundId 부여, PG 예외 시 status=FAILED·pgRefundId null이다(D-67).
 * pgRefundId는 nullable이다(Refund.pg_refund_id 컬럼 정합).
 */
public record AdminRefundInitiateResponse(
        String refundPublicId,
        String claimPublicId,
        RefundStatus status,
        Long amount,
        String pgRefundId) {

    /** 영속 Refund + Controller가 보유한 claimPublicId로 응답을 조립한다. */
    public static AdminRefundInitiateResponse from(String claimPublicId, Refund refund) {
        return new AdminRefundInitiateResponse(
                refund.getPublicId(),
                claimPublicId,
                refund.getStatus(),
                refund.getAmount(),
                refund.getPgRefundId());
    }
}

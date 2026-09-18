package com.zslab.mall.settlement.controller.response;

import com.zslab.mall.settlement.service.SettlementRegenerateResult;

/**
 * 정산 재생성 응답(Track 85). 재집계 대상 품목이 없으면 settlementId·금액이 null(deletedOnly=true)이다.
 */
public record SettlementRegenerateResponse(
        Long deletedSettlementId,
        boolean deletedOnly,
        Long settlementId,
        Long grossAmount,
        Long feeAmount,
        Long refundAmount,
        Long netAmount) {

    public static SettlementRegenerateResponse from(SettlementRegenerateResult result) {
        if (result.regenerated() == null) {
            return new SettlementRegenerateResponse(result.deletedSettlementId(), true, null, null, null, null, null);
        }
        return new SettlementRegenerateResponse(
                result.deletedSettlementId(), false, result.regenerated().getId(),
                result.regenerated().getGrossAmount(), result.regenerated().getFeeAmount(),
                result.regenerated().getRefundAmount(), result.regenerated().getNetAmount());
    }
}

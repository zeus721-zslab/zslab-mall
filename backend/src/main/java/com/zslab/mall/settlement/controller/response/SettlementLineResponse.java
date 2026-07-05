package com.zslab.mall.settlement.controller.response;

import com.zslab.mall.settlement.entity.Settlement;

/**
 * 생성된 정산 1건 요약(Track 48 P3). seller별 gross·fee·commissionRate 스냅샷·refund·net을 노출한다.
 */
public record SettlementLineResponse(
        Long sellerId,
        long grossAmount,
        long feeAmount,
        int commissionRate,
        long refundAmount,
        long netAmount) {

    public static SettlementLineResponse from(Settlement settlement) {
        return new SettlementLineResponse(
                settlement.getSellerId(),
                settlement.getGrossAmount(),
                settlement.getFeeAmount(),
                settlement.getCommissionRate(),
                settlement.getRefundAmount(),
                settlement.getNetAmount());
    }
}

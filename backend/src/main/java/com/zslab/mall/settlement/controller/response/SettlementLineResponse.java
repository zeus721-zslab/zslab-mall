package com.zslab.mall.settlement.controller.response;

import com.zslab.mall.settlement.entity.Settlement;
import java.time.LocalDate;

/**
 * 생성된 정산 1건 요약(Track 48 P3·Track 85 품목 스냅샷 전환). seller별 gross·fee·refund·net과 정산 id·지급예정일을 노출한다.
 * 헤더 commissionRate는 Track 85부터 품목별 율이 SoT라 응답에서 제외했다.
 */
public record SettlementLineResponse(
        Long settlementId,
        Long sellerId,
        long grossAmount,
        long feeAmount,
        long refundAmount,
        long netAmount,
        LocalDate scheduledPayDate) {

    public static SettlementLineResponse from(Settlement settlement) {
        return new SettlementLineResponse(
                settlement.getId(),
                settlement.getSellerId(),
                settlement.getGrossAmount(),
                settlement.getFeeAmount(),
                settlement.getRefundAmount(),
                settlement.getNetAmount(),
                settlement.getScheduledPayDate());
    }
}

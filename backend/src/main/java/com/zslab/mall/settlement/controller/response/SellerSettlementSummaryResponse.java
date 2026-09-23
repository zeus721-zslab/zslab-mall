package com.zslab.mall.settlement.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 셀러 정산 목록 행(Track 85·본인 CONFIRMED·PAID만). */
public record SellerSettlementSummaryResponse(
        Long id,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime periodStart,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime periodEnd,
        long grossAmount,
        long feeAmount,
        long refundAmount,
        long carryoverAmount,
        long netAmount,
        SettlementStatus status,
        LocalDate scheduledPayDate,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime paidAt) {

    public static SellerSettlementSummaryResponse from(Settlement settlement) {
        return new SellerSettlementSummaryResponse(settlement.getId(), settlement.getPeriodStart(),
                settlement.getPeriodEnd(), settlement.getGrossAmount(), settlement.getFeeAmount(),
                settlement.getRefundAmount(), settlement.getCarryoverAmount(), settlement.getNetAmount(), settlement.getStatus(),
                settlement.getScheduledPayDate(), settlement.getPaidAt());
    }
}

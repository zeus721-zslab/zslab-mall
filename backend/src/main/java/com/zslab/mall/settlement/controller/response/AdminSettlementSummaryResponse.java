package com.zslab.mall.settlement.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 관리자 정산 목록 행(Track 85). bankAccountRegistered = 셀러의 현재 주 계좌 유무(스냅샷 아님)·saleItemCount = SALE 품목 건수.
 */
public record AdminSettlementSummaryResponse(
        Long id,
        SettlementSellerRef seller,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime periodStart,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime periodEnd,
        long grossAmount,
        long feeAmount,
        long refundAmount,
        long carryoverAmount,
        long netAmount,
        SettlementStatus status,
        LocalDate scheduledPayDate,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime paidAt,
        boolean bankAccountRegistered,
        long saleItemCount) {

    public static AdminSettlementSummaryResponse of(Settlement settlement, SettlementSellerRef seller,
            boolean bankAccountRegistered, long saleItemCount) {
        return new AdminSettlementSummaryResponse(settlement.getId(), seller, settlement.getPeriodStart(),
                settlement.getPeriodEnd(), settlement.getGrossAmount(), settlement.getFeeAmount(),
                settlement.getRefundAmount(), settlement.getCarryoverAmount(), settlement.getNetAmount(), settlement.getStatus(),
                settlement.getScheduledPayDate(), settlement.getPaidAt(), bankAccountRegistered, saleItemCount);
    }
}

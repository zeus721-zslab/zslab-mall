package com.zslab.mall.settlement.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 셀러 정산 상세(Track 85). 목록 행 + 품목 건수 + 계좌(끝 4자리·스냅샷 우선·없으면 현재 주 계좌·없으면 null). */
public record SellerSettlementDetailResponse(
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
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime paidAt,
        long saleItemCount,
        long refundItemCount,
        long carryoverItemCount,
        SettlementBankAccountResponse bankAccount) {

    public static SellerSettlementDetailResponse of(SellerSettlementSummaryResponse summary, long saleItemCount,
            long refundItemCount, long carryoverItemCount, SettlementBankAccountResponse bankAccount) {
        return new SellerSettlementDetailResponse(summary.id(), summary.periodStart(), summary.periodEnd(),
                summary.grossAmount(), summary.feeAmount(), summary.refundAmount(), summary.carryoverAmount(),
                summary.netAmount(), summary.status(), summary.scheduledPayDate(), summary.paidAt(), saleItemCount,
                refundItemCount, carryoverItemCount, bankAccount);
    }
}

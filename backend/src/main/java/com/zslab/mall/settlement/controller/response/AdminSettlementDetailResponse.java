package com.zslab.mall.settlement.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 관리자 정산 상세(Track 85). 목록 행 + 품목 건수 + 셀러 연락처(마스킹) + 계좌(스냅샷 우선·없으면 현재 주 계좌·없으면 null).
 */
public record AdminSettlementDetailResponse(
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
        long saleItemCount,
        long refundItemCount,
        long carryoverItemCount,
        SettlementSellerContactResponse sellerContact,
        SettlementBankAccountResponse bankAccount) {

    public static AdminSettlementDetailResponse of(AdminSettlementSummaryResponse summary, long refundItemCount,
            long carryoverItemCount, SettlementSellerContactResponse sellerContact, SettlementBankAccountResponse bankAccount) {
        return new AdminSettlementDetailResponse(summary.id(), summary.seller(), summary.periodStart(),
                summary.periodEnd(), summary.grossAmount(), summary.feeAmount(), summary.refundAmount(),
                summary.carryoverAmount(), summary.netAmount(), summary.status(), summary.scheduledPayDate(), summary.paidAt(),
                summary.bankAccountRegistered(), summary.saleItemCount(), refundItemCount, carryoverItemCount, sellerContact,
                bankAccount);
    }
}

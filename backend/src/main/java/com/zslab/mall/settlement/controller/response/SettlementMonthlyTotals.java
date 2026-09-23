package com.zslab.mall.settlement.controller.response;

/** 해당 월 정산 합계(상태·keyword 필터 무관·월 전체·Track 85). */
public record SettlementMonthlyTotals(
        long grossAmount,
        long feeAmount,
        long refundAmount,
        long carryoverAmount,
        long netAmount,
        long pendingCount,
        long confirmedCount,
        long paidCount) {
}

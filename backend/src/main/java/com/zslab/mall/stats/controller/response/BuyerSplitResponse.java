package com.zslab.mall.stats.controller.response;

/** 1회 구매자 vs 재구매자(기간 내 2회 이상 결제) 인원·매출 분리(Track 88). 매출은 order.total_price 합(paid_at 귀속). */
public record BuyerSplitResponse(
        long firstTimeBuyerCount,
        long firstTimeRevenue,
        long repeatBuyerCount,
        long repeatRevenue) {
}

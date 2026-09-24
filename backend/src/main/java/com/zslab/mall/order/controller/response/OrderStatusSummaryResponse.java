package com.zslab.mall.order.controller.response;

/**
 * 구매자 주문 현황 요약 응답(Track 105-2d 마이페이지). stages는 최근 periodMonths개월(주문일 기준) 주문 품목의 단계별 건수이며
 * 0건 단계도 0으로 채운다. activeClaimCount는 기간 제한 없는 진행 중(REQUESTED·APPROVED) 클레임 수다.
 */
public record OrderStatusSummaryResponse(
        int periodMonths,
        Stages stages,
        long activeClaimCount) {

    /** 품목 상태 1:1 단계별 건수(PAID·PREPARING·SHIPPING·DELIVERED·CONFIRMED). */
    public record Stages(long paid, long preparing, long shipping, long delivered, long confirmed) {
    }
}

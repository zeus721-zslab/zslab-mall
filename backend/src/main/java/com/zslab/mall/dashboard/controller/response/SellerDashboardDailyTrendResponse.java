package com.zslab.mall.dashboard.controller.response;

/** 일별 추이 1행(기간 내 매일·빈 날 0). date는 "yyyy-MM-dd"·orderCount는 DISTINCT 주문 수·revenue는 자기 품목 합. 차트 2종이 여기서 파생된다. */
public record SellerDashboardDailyTrendResponse(
        String date,
        long orderCount,
        long revenue) {
}

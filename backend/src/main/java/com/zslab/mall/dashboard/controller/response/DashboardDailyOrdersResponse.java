package com.zslab.mall.dashboard.controller.response;

/** 일별 주문 1행(최근 30일·오늘 포함·빈 날 0). date는 "yyyy-MM-dd". */
public record DashboardDailyOrdersResponse(
        String date,
        long orderCount,
        long revenue) {
}

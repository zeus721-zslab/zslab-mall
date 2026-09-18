package com.zslab.mall.dashboard.controller.response;

/** 월별 매출 1행(최근 6개월·당월 포함·빈 달 0). yearMonth는 "yyyy-MM". */
public record DashboardMonthlyRevenueResponse(
        String yearMonth,
        long revenue,
        long refund,
        long netRevenue,
        long orderCount) {

    public static DashboardMonthlyRevenueResponse of(String yearMonth, long revenue, long refund, long orderCount) {
        return new DashboardMonthlyRevenueResponse(yearMonth, revenue, refund, revenue - refund, orderCount);
    }
}

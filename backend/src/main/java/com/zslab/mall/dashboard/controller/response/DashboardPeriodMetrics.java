package com.zslab.mall.dashboard.controller.response;

/**
 * 기간 지표 1묶음(Track 86·D-180). revenue = 결제완료(order.paid_at) 주문 total_price 합·refund = COMPLETED 환불(refunded_at) 합·
 * netRevenue = revenue − refund. 비교 기간(전일·전월)도 같은 형태로 내려 증감률은 FE가 계산한다.
 */
public record DashboardPeriodMetrics(
        long revenue,
        long refund,
        long netRevenue,
        long orderCount,
        long newMemberCount) {

    public static DashboardPeriodMetrics of(long revenue, long refund, long orderCount, long newMemberCount) {
        return new DashboardPeriodMetrics(revenue, refund, revenue - refund, orderCount, newMemberCount);
    }
}

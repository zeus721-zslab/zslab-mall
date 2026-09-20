package com.zslab.mall.dashboard.controller.response;

/**
 * 기간 요약(Track 90-B-2). revenue = 결제완료(order.paid_at) 주문의 자기 품목 total_price 합·refund = 자기 품목 클레임의 COMPLETED
 * 환불(refunded_at) 합·netRevenue = revenue − refund·orderCount = 자기 품목이 포함된 주문 수(COUNT DISTINCT order).
 */
public record SellerDashboardSummaryResponse(
        long revenue,
        long refund,
        long netRevenue,
        long orderCount) {
    public static SellerDashboardSummaryResponse of(long revenue, long refund, long orderCount) {
        return new SellerDashboardSummaryResponse(revenue, refund, revenue - refund, orderCount);
    }
}

package com.zslab.mall.dashboard.repository;

/**
 * 셀러 기간 매출 집계 1행(Track 90-B-2). orderCount는 자기 품목이 포함된 주문 수(COUNT DISTINCT order)·revenue는 자기 품목 total_price 합.
 */
public interface SellerDashboardSalesProjection {
    Long getOrderCount();
    Long getRevenue();
}

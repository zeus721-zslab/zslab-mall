package com.zslab.mall.dashboard.repository;

/** 기간 결제완료 주문 집계(Track 86). discount·shippingFee는 산식 근거용으로 함께 집계하되 응답에는 노출하지 않는다(D-180). */
public interface DashboardSalesProjection {

    Long getOrderCount();

    Long getRevenue();

    Long getDiscountAmount();

    Long getShippingFee();
}

package com.zslab.mall.dashboard.repository;

/** 이번 달 매출 상위 상품 1행(Track 86). productName은 order_item 스냅샷(MAX)·productId는 public_id enrich용. */
public interface DashboardTopProductProjection {

    Long getProductId();

    String getProductName();

    Long getRevenue();

    Long getQuantity();
}

package com.zslab.mall.dashboard.repository;

/** 이번 달 매출 상위 셀러 1행(Track 86). sellerId는 서비스에서 seller public_id·상호 배치 enrich에 쓴다. */
public interface DashboardTopSellerProjection {

    Long getSellerId();

    Long getRevenue();

    Long getOrderItemCount();
}

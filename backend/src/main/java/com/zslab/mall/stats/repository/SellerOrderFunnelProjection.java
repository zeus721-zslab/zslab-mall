package com.zslab.mall.stats.repository;

/** 셀러 결제 코호트 퍼널 3단계 집계 1행(Track 90-E-2). 행 0건이어도 COALESCE로 0 행 1개가 온다. */
public interface SellerOrderFunnelProjection {
    Long getPaidItems();
    Long getShippedItems();
    Long getDeliveredItems();
}

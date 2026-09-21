package com.zslab.mall.stats.repository;

/** 판매 중 옵션 재고 현황 1행(Track 90-E-3). soldOutCount = 가용 재고 0 옵션 수·totalCount = 판매 중 옵션 수. 행 0건이어도 COALESCE로 0 행 1개가 온다. */
public interface SellerOptionStockProjection {
    Long getSoldOutCount();
    Long getTotalCount();
}

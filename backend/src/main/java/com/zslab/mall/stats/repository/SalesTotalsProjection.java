package com.zslab.mall.stats.repository;

/** 기간 결제완료 주문 건수·total_price 합(Track 87). 행 0건이어도 COALESCE로 0 행 1개가 온다. */
public interface SalesTotalsProjection {

    Long getOrderCount();

    Long getRevenue();
}

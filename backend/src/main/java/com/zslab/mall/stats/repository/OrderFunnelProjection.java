package com.zslab.mall.stats.repository;

/** 결제 코호트 퍼널 집계 1행(Track 88). 코호트가 비면 전부 0(COALESCE). */
public interface OrderFunnelProjection {

    Long getPaidItems();

    Long getShippedItems();

    Long getDeliveredItems();

    Long getConfirmedItems();

    Long getCancelledItems();

    Long getReturnedItems();
}

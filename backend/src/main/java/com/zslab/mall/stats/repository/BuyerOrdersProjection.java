package com.zslab.mall.stats.repository;

/** 구매자별 기간 결제 주문 건수·매출 합(Track 88·재구매·1회/재구매 분리·상위 회원 산출 원천). */
public interface BuyerOrdersProjection {

    Long getBuyerId();

    Long getOrderCount();

    Long getRevenue();
}

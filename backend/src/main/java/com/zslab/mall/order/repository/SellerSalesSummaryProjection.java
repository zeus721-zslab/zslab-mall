package com.zslab.mall.order.repository;

/** 셀러 누적 거래 요약 projection(Track 89-D 관리자 셀러 상세): 결제 이력 있는 주문 수·구매확정 매출 합. */
public interface SellerSalesSummaryProjection {

    Long getOrderCount();

    Long getConfirmedAmount();
}

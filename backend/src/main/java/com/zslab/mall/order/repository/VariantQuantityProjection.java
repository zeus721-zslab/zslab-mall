package com.zslab.mall.order.repository;

/**
 * variant별 품목 수량 합계 projection(SEC-02 잔여 · 구매자 미결제 주문의 variant별 예약 수량).
 */
public interface VariantQuantityProjection {

    Long getVariantId();

    Long getQuantity();
}

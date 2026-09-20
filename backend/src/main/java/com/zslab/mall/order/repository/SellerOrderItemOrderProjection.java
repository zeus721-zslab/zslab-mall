package com.zslab.mall.order.repository;

import java.time.LocalDateTime;

/** 셀러 품목 조회용 주문 축 표시값(Track 90-B-1). 주문번호·시각만 — 구매자·금액 등 셀러 노출 금지 필드는 없다. */
public interface SellerOrderItemOrderProjection {
    Long getOrderItemId();

    String getOrderNo();

    LocalDateTime getOrderedAt();

    LocalDateTime getPaidAt();
}

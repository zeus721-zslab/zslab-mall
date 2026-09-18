package com.zslab.mall.order.repository;

import java.time.LocalDateTime;

/**
 * 정산 SALE 품목 스냅샷 소스 projection(Track 85). 기간 내 구매확정 order_item + 소속 주문 public_id.
 */
public interface SettlementSaleSourceProjection {

    Long getOrderItemId();

    Long getSellerId();

    String getOrderPublicId();

    String getProductName();

    String getOptionLabel();

    Integer getQuantity();

    Long getAmount();

    Integer getCommissionRate();

    LocalDateTime getConfirmedAt();
}

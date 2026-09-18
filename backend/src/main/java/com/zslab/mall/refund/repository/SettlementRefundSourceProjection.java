package com.zslab.mall.refund.repository;

import java.time.LocalDateTime;

/**
 * 정산 REFUND 품목 스냅샷 소스 projection(Track 85). 기간 내 완료 환불 + 귀속 order_item·주문 public_id.
 */
public interface SettlementRefundSourceProjection {

    Long getRefundId();

    Long getAmount();

    LocalDateTime getRefundedAt();

    Long getOrderItemId();

    Long getSellerId();

    String getOrderPublicId();

    String getProductName();

    String getOptionLabel();

    Integer getQuantity();

    Integer getCommissionRate();
}

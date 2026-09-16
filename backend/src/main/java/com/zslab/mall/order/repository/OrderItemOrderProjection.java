package com.zslab.mall.order.repository;

/**
 * 주문 품목 id → 소속 주문 요약(id·public_id·주문번호·구매자 id) 경량 projection(Track 80 관리자 클레임 목록·
 * {@code OrderItemRepository.findOrderSummariesByIdIn}). Order 엔티티를 적재하면 shippingSnapshot(OneToOne mappedBy·LAZY 불가)이
 * 주문마다 추가 SELECT를 내므로 스칼라 projection으로 읽는다.
 */
public interface OrderItemOrderProjection {

    Long getOrderItemId();

    Long getOrderId();

    String getOrderPublicId();

    String getOrderNo();

    Long getBuyerId();
}

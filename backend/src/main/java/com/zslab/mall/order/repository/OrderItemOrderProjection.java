package com.zslab.mall.order.repository;

/**
 * 주문 품목 id → 소속 주문 요약(id·public_id·주문번호·구매자 id) + 품목 상품명 경량 projection(Track 80 관리자 클레임 목록·
 * {@code OrderItemRepository.findOrderSummariesByIdIn}). Order 엔티티를 적재하면 shippingSnapshot(OneToOne mappedBy·LAZY 불가)이
 * 주문마다 추가 SELECT를 내므로 스칼라 projection으로 읽는다.
 */
public interface OrderItemOrderProjection {

    Long getOrderItemId();

    Long getOrderId();

    String getOrderPublicId();

    String getOrderNo();

    Long getBuyerId();

    /** 주문 시점 상품명 스냅샷({@code order_item.product_name}·Track 76 V22). Track 101-B 구매자 클레임 목록이 쓴다. */
    String getProductName();

    /** 품목 상품 id({@code order_item.product_id}). Track 105-4b 구매자 클레임 목록 썸네일 배치 조회 키다. */
    Long getProductId();
}

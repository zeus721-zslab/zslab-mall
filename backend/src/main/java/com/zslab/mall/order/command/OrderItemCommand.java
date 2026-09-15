package com.zslab.mall.order.command;

/**
 * 주문 품목 생성 입력(Service 계층 Command). 형식 검증은 OrderService·도메인 규칙은 OrderItem.create가 담당한다.
 * optionLabel은 주문 시점 옵션 라벨 스냅샷(null 허용·Track 75).
 */
public record OrderItemCommand(
        Long productId,
        Long variantId,
        Long sellerId,
        int quantity,
        Long unitPrice,
        Long totalPrice,
        String optionLabel) {

    /** 옵션 라벨 없는 생성(기존 호출 호환). */
    public OrderItemCommand(Long productId, Long variantId, Long sellerId, int quantity, Long unitPrice, Long totalPrice) {
        this(productId, variantId, sellerId, quantity, unitPrice, totalPrice, null);
    }
}

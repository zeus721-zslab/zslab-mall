package com.zslab.mall.order.command;

/**
 * 주문 품목 생성 입력(Service 계층 Command). 형식 검증은 OrderService·도메인 규칙은 OrderItem.create가 담당한다.
 */
public record OrderItemCommand(
        Long productId,
        Long variantId,
        Long sellerId,
        int quantity,
        Long unitPrice,
        Long totalPrice) {
}

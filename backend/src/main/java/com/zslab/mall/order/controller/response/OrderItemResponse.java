package com.zslab.mall.order.controller.response;

/**
 * 주문 품목 응답(§11). 식별자(orderItemId·productId·variantId)는 전부 public_id·내부 BIGINT 미노출.
 */
public record OrderItemResponse(
        String orderItemId,
        String productId,
        String variantId,
        int quantity,
        long unitPrice,
        long totalPrice) {
}

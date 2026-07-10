package com.zslab.mall.order.controller.response;

/**
 * 주문 품목 응답(§11). 식별자(orderItemId·productId·variantId)는 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>productName은 표시용 enrich 값(public_id 아님). 삭제 상품(productById miss) 시 null → §15 NON_NULL로 생략된다.
 *
 * <p>status는 품목 상태(item_status)를 order.status와 동일한 {@link StatusView} 표현으로 노출한다.
 */
public record OrderItemResponse(
        String orderItemId,
        String productId,
        String productName,
        String variantId,
        int quantity,
        long unitPrice,
        long totalPrice,
        StatusView status) {
}

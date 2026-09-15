package com.zslab.mall.order.controller.response;

/**
 * 주문 품목 응답(§11). 식별자(orderItemId·productId·variantId)는 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>productName은 표시용 enrich 값(public_id 아님). 삭제 상품(productById miss) 시 null → §15 NON_NULL로 생략된다.
 *

 * <p>optionLabel은 order_item.option_label 스냅샷 그대로(주문 시점 옵션 라벨·Track 75·D-164). 옵션 없는 단순상품·V20 이전 주문은 null.
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
        String optionLabel,
        StatusView status) {
}

package com.zslab.mall.order.controller.response;

/**
 * 주문 품목 응답(§11). 식별자(orderItemId·productId·variantId)는 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>productName은 order_item.product_name 스냅샷(주문 시점 상품명·Track 76·V22). 이후 상품명 수정·삭제와 무관하게 항상 노출된다.
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

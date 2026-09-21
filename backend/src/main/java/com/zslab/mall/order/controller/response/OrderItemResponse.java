package com.zslab.mall.order.controller.response;

/**
 * 주문 품목 응답(§11). 식별자(orderItemId·productId·variantId)는 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>productName은 order_item.product_name 스냅샷(주문 시점 상품명·Track 76·V22). 이후 상품명 수정·삭제와 무관하게 항상 노출된다.
 *

 * <p>optionLabel은 order_item.option_label 스냅샷 그대로(주문 시점 옵션 라벨·Track 75·D-164). 옵션 없는 단순상품·V20 이전 주문은 null.
 *
 * <p>status는 품목 상태(item_status)를 order.status와 동일한 {@link StatusView} 표현으로 노출한다.
 *
 * <p>exchangeCompleted는 이 품목에 완료된 교환(EXCHANGE·COMPLETED)이 있는지(Track 83 D-177 보충·FE-30-4). 교환 완료 품목은 DELIVERED로
 * 복귀하지만 재교환은 422이므로 FE가 교환 버튼을 숨기는 데 쓴다(반품 버튼은 유지). 추가형 필드.
 *
 * <p>delivery는 원 발송(OUTBOUND·클레임 미연결) 최신 배송 정보(Track 96-2 D-203·C-05). 송장 미등록이면 null. 추가형 필드.
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
        StatusView status,
        boolean exchangeCompleted,
        OrderItemDeliveryResponse delivery) {
}

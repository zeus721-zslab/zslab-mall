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
 *
 * <p>thumbnailUrl은 상품의 현재 product.thumbnail_url(Track 105-2d·D-223·장바구니·상품 목록과 같은 축소본). 삭제 상품·썸네일 없음은
 * null(NON_NULL로 키 생략). 추가형 필드.
 *
 * <p>review는 품목의 리뷰 상태(Track 106-1·{@link OrderItemReviewResponse}). 추가형 필드.
 *
 * <p>inspectionFailed는 이 품목에 검수 불합격(inspection_result FAIL) 이력 클레임이 있는지(W2·유형 무관). 반품·교환 재요청이 422인
 * 서버 조건(ClaimService 반품·교환 요청 가드)과 같은 기준이며 FE가 반품·교환 버튼을 숨기는 데 쓴다. 추가형 필드.
 *
 * <p>exchangeDelivery는 교환 클레임(EXCHANGE·검수 FAIL 아님)에 연결된 OUTBOUND 최신 1건(W7·교환품 발송). 검수 FAIL 재발송은 원 상품을
 * 되돌려 보내는 배송이라 제외한다. 없으면 null. 추가형 필드.
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
        OrderItemDeliveryResponse delivery,
        String thumbnailUrl,
        OrderItemReviewResponse review,
        boolean inspectionFailed,
        OrderItemDeliveryResponse exchangeDelivery) {
}

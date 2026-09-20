package com.zslab.mall.order.controller.response;

import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.SellerOrderItemOrderProjection;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/**
 * 셀러 품목 목록 행(Track 90-B-1). 관리자 {@code AdminOrderSummaryResponse}와 별도 record다 — 관리자 DTO를 재사용하면 필드가 늘어날 때
 * 셀러에게 조용히 샌다. 셀러 노출 금지(구매자 이름·이메일·타 셀러 품목·sellerNames·주문 총액·결제 상세·취소 사유 액터·관리자 actions)는
 * 애초에 필드가 없다. 응답 키 집합은 통합 테스트가 화이트리스트로 고정한다.
 *
 * @param orderNo       주문번호(참조 표시용·주문 축 필드는 번호·시각뿐)
 * @param recipientName 배송지 수령인명(주문자가 아니라 배송지 스냅샷)
 * @param delivery      원 발송 최신 배송(송장 미등록이면 null)
 * @param claim         요청일 최신 클레임 요약(없으면 null·Track 90-D-1)
 * @param claimCount    품목의 클레임 총 건수(거부·종결 이력 포함)
 */
public record SellerOrderItemSummaryResponse(
        String orderItemId,
        String orderNo,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime orderedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime paidAt,
        String productName,
        String optionLabel,
        int quantity,
        long unitPrice,
        long totalPrice,
        OrderItemStatus itemStatus,
        String recipientName,
        SellerOrderItemDeliveryResponse delivery,
        SellerOrderItemClaimResponse claim,
        long claimCount) {

    public static SellerOrderItemSummaryResponse of(OrderItem item, SellerOrderItemOrderProjection order, String recipientName,
            SellerOrderItemDeliveryResponse delivery, SellerOrderItemClaimResponse claim, long claimCount) {
        return new SellerOrderItemSummaryResponse(item.getPublicId(),
                order == null ? null : order.getOrderNo(),
                order == null ? null : order.getOrderedAt(),
                order == null ? null : order.getPaidAt(),
                item.getProductName(), item.getOptionLabel(), item.getQuantity(), item.getUnitPrice(), item.getTotalPrice(),
                item.getItemStatus(), recipientName, delivery, claim, claimCount);
    }
}

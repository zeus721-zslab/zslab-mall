package com.zslab.mall.delivery.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemOrderProjection;
import java.time.LocalDateTime;

/**
 * 셀러 배송 목록 행(Track 90-B-1). 관리자 {@code AdminDeliverySummaryResponse}와 별도 record — 관리자 DTO를 재사용하면 필드가 늘어날 때
 * 셀러에게 조용히 샌다. 주문 축은 주문번호만(관리자의 orderId는 주문 상세 이동용이라 제외)·품목 축은 품목 public_id·옵션·수량을 더한다
 * (셀러 품목 상세·배송완료 mark-delivered의 키). 응답 키 집합은 통합 테스트가 화이트리스트로 고정한다.
 *
 * @param recipientName 배송지 수령인명(주문 배송지 스냅샷·주문자 아님)
 * @param claimId       연계 클레임 public_id(clm_)·원 발송은 null
 * @param claimType     연계 클레임 유형·원 발송은 null
 */
public record SellerDeliverySummaryResponse(
        String deliveryId,
        String orderItemId,
        String orderNo,
        String productName,
        String optionLabel,
        int quantity,
        String recipientName,
        DeliveryDirection direction,
        DeliveryStatus status,
        DeliveryCarrier carrier,
        String trackingNo,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime shippedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime deliveredAt,
        String claimId,
        ClaimType claimType) {

    /** 배치 enrich 결과로 조립한다. 품목·주문·클레임은 무결성 위반(부재) 시 null 허용(목록이 죽지 않게). */
    public static SellerDeliverySummaryResponse from(Delivery delivery, OrderItem item, OrderItemOrderProjection order,
            String recipientName, Claim claim) {
        return new SellerDeliverySummaryResponse(
                delivery.getPublicId(),
                item == null ? null : item.getPublicId(),
                order == null ? null : order.getOrderNo(),
                item == null ? null : item.getProductName(),
                item == null ? null : item.getOptionLabel(),
                item == null ? 0 : item.getQuantity(),
                recipientName,
                delivery.getDirection(),
                delivery.getStatus(),
                delivery.getCarrier(),
                delivery.getTrackingNo(),
                delivery.getShippedAt(),
                delivery.getDeliveredAt(),
                claim == null ? null : claim.getPublicId(),
                claim == null ? null : claim.getType());
    }
}

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
 * 관리자 배송 목록 행(Track 89-B D-184). 배송(delivery) 행 단위이며 식별자는 전부 public_id다.
 *
 * @param deliveryId    배송 public_id(dlv_)
 * @param orderId       주문 public_id(ord_)·주문 상세 이동용
 * @param orderNo       주문번호
 * @param productName   상품명 스냅샷
 * @param recipientName 배송지 수령인명(주문 배송지 스냅샷)
 * @param direction     OUTBOUND 발송 / RETURN 회수
 * @param claimId       연계 클레임 public_id(clm_)·원 발송은 null
 * @param claimType     연계 클레임 유형·원 발송은 null
 */
public record AdminDeliverySummaryResponse(
        String deliveryId,
        String orderId,
        String orderNo,
        String productName,
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
    public static AdminDeliverySummaryResponse from(Delivery delivery, OrderItem item, OrderItemOrderProjection order,
            String recipientName, Claim claim) {
        return new AdminDeliverySummaryResponse(
                delivery.getPublicId(),
                order == null ? null : order.getOrderPublicId(),
                order == null ? null : order.getOrderNo(),
                item == null ? null : item.getProductName(),
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

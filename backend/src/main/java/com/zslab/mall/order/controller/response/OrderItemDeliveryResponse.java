package com.zslab.mall.order.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import java.time.LocalDateTime;

/**
 * 구매자 품목의 원 발송 배송 정보(Track 96-2 D-203·C-05). 원 발송 = OUTBOUND이면서 클레임 미연결(claim_id NULL) Delivery의 최신 1건.
 * 교환품·재발송(claim_id 연결)은 클레임 상세({@code ClaimResponse.reshipment})가 담당하고 반품 회수(RETURN)는 품목 배송이 아니다.
 * 송장 미등록 품목은 null. 식별자(deliveryId)는 구매자 조작이 없어 노출하지 않는다.
 */
public record OrderItemDeliveryResponse(
        DeliveryCarrier carrier,
        String trackingNo,
        DeliveryStatus status,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime shippedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime deliveredAt) {

    public static OrderItemDeliveryResponse from(Delivery delivery) {
        if (delivery == null) {
            return null;
        }
        return new OrderItemDeliveryResponse(delivery.getCarrier(), delivery.getTrackingNo(), delivery.getStatus(),
                delivery.getShippedAt(), delivery.getDeliveredAt());
    }
}

package com.zslab.mall.claim.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import java.time.LocalDateTime;

/**
 * 클레임 연결 Delivery 요약(Track 81-A D-170). 반품 회수(RETURN)·검수 불합격 재발송(OUTBOUND) 응답과 클레임 응답의 회수 송장 필드에 공용.
 */
public record ReturnShipmentResponse(
        String deliveryPublicId,
        DeliveryDirection direction,
        DeliveryCarrier carrier,
        String trackingNo,
        DeliveryStatus status,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime shippedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime deliveredAt) {

    public static ReturnShipmentResponse from(Delivery delivery) {
        return new ReturnShipmentResponse(delivery.getPublicId(), delivery.getDirection(), delivery.getCarrier(),
                delivery.getTrackingNo(), delivery.getStatus(), delivery.getShippedAt(), delivery.getDeliveredAt());
    }
}

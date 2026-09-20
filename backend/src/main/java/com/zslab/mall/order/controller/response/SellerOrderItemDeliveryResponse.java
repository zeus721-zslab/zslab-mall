package com.zslab.mall.order.controller.response;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/** 셀러 품목의 원 발송(OUTBOUND) 최신 배송 행(Track 90-B-1). 송장 미등록 품목은 null. */
public record SellerOrderItemDeliveryResponse(
        String deliveryId,
        DeliveryCarrier carrier,
        String trackingNo,
        DeliveryStatus status,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime shippedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime deliveredAt) {

    public static SellerOrderItemDeliveryResponse from(Delivery delivery) {
        if (delivery == null) {
            return null;
        }
        return new SellerOrderItemDeliveryResponse(delivery.getPublicId(), delivery.getCarrier(), delivery.getTrackingNo(),
                delivery.getStatus(), delivery.getShippedAt(), delivery.getDeliveredAt());
    }
}

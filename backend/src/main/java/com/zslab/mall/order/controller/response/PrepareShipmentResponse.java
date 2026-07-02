package com.zslab.mall.order.controller.response;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;

/**
 * 일반 주문 배송 개시(prepare-shipment) 응답 DTO(Track 23). 생성·발송된 Delivery 관점만 노출한다.
 *
 * <p>박제 시점 status는 SHIPPING이다(단일 트랜잭션에서 READY→SHIPPING 전이 후 반환·state-machine §6.1). 일반 주문이므로
 * claim_id는 노출하지 않는다(항상 NULL·D-98 Q13).
 */
public record PrepareShipmentResponse(
        String deliveryPublicId,
        DeliveryStatus status,
        DeliveryCarrier carrier,
        String trackingNo) {

    public static PrepareShipmentResponse from(Delivery delivery) {
        return new PrepareShipmentResponse(
                delivery.getPublicId(),
                delivery.getStatus(),
                delivery.getCarrier(),
                delivery.getTrackingNo());
    }
}

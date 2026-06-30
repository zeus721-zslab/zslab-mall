package com.zslab.mall.delivery.controller.response;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;

/**
 * 교환품 출고 등록 응답 DTO(D-99 응답 계약 박제). Delivery 관점만 노출한다.
 *
 * <p>Claim 상태·{@code exchangeDeliveryId}·Delivery 존재 여부는 포함하지 않는다(Controller 단일 책임·cross-tenant 정보 노출 회피·D-99 Q10).
 * 박제 시점 status는 SHIPPING이다(D-98 Q3 단일 트랜잭션·state-machine §6.1 READY 의미 정합).
 */
public record RegisterExchangeShipmentResponse(
        String deliveryPublicId,
        DeliveryStatus status,
        DeliveryCarrier carrier,
        String trackingNo) {

    public static RegisterExchangeShipmentResponse from(Delivery delivery) {
        return new RegisterExchangeShipmentResponse(
                delivery.getPublicId(),
                delivery.getStatus(),
                delivery.getCarrier(),
                delivery.getTrackingNo());
    }
}

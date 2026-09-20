package com.zslab.mall.delivery.controller.response;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;

/**
 * 셀러 송장 정정 응답(Track 90-B-1·외부 검토 r2). 정정 반영 Delivery 관점 4필드만 — 관리자·공용 {@code RegisterExchangeShipmentResponse}를
 * 재사용하지 않는다(공용 DTO에 필드가 늘면 셀러에게 조용히 샌다·r1 원칙). 응답 키 집합은 통합 테스트가 화이트리스트로 고정한다.
 */
public record SellerDeliveryTrackingCorrectionResponse(
        String deliveryPublicId,
        DeliveryStatus status,
        DeliveryCarrier carrier,
        String trackingNo) {

    public static SellerDeliveryTrackingCorrectionResponse from(Delivery delivery) {
        return new SellerDeliveryTrackingCorrectionResponse(
                delivery.getPublicId(),
                delivery.getStatus(),
                delivery.getCarrier(),
                delivery.getTrackingNo());
    }
}

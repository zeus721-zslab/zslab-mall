package com.zslab.mall.order.controller.request;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 일반 주문 배송 개시(prepare-shipment) 요청 DTO(Track 23·RegisterExchangeShipmentRequest 패턴 1:1). 형식 검증만 담당하며
 * 권한·상태 판단은 {@code OrderShippingService}가 한다.
 *
 * <p>carrier는 enum 직접 바인딩으로 형식 검증한다(@ValidEnum 미신설·Jackson 역직렬화 실패 → 400). 누락은 fieldErrors 400(D-227).
 * trackingNo는 앞뒤 공백 제거 후 {@link Delivery#TRACKING_NO_PATTERN}으로 검증한다(D-227).
 */
public record PrepareShipmentRequest(
        @NotNull DeliveryCarrier carrier,
        @NotNull(message = Delivery.TRACKING_NO_FORMAT_MESSAGE)
        @Pattern(regexp = Delivery.TRACKING_NO_PATTERN, message = Delivery.TRACKING_NO_FORMAT_MESSAGE) String trackingNo) {

    public PrepareShipmentRequest {
        trackingNo = Delivery.stripTrackingNo(trackingNo);
    }
}

package com.zslab.mall.order.controller.request;

import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 일반 주문 배송 개시(prepare-shipment) 요청 DTO(Track 23·RegisterExchangeShipmentRequest 패턴 1:1). 형식 검증만 담당하며
 * 권한·상태 판단은 {@code OrderShippingService}가 한다.
 *
 * <p>carrier는 enum 직접 바인딩으로 형식 검증한다(@ValidEnum 미신설·Jackson 역직렬화 실패 → 400).
 * trackingNo의 {@code @Size(max=100)}은 {@code delivery.tracking_no VARCHAR(100)} 정합이다.
 */
public record PrepareShipmentRequest(
        DeliveryCarrier carrier,
        @NotBlank @Size(max = 100) String trackingNo) {
}

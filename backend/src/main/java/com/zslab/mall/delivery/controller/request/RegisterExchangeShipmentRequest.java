package com.zslab.mall.delivery.controller.request;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 교환품 출고 등록 요청 DTO(D-99 Q3 α·ClaimRequestRequest 패턴 1:1). 형식 검증만 담당하며 권한·상태 판단은 ClaimService가 한다.
 *
 * <p>carrier는 enum 직접 바인딩으로 형식 검증한다(@ValidEnum 미신설·D-99 Q4 β·Jackson 역직렬화 실패 → 400).
 * trackingNo는 앞뒤 공백 제거 후 {@link Delivery#TRACKING_NO_PATTERN}으로 검증한다(D-227).
 */
public record RegisterExchangeShipmentRequest(
        DeliveryCarrier carrier,
        @NotNull(message = Delivery.TRACKING_NO_FORMAT_MESSAGE)
        @Pattern(regexp = Delivery.TRACKING_NO_PATTERN, message = Delivery.TRACKING_NO_FORMAT_MESSAGE) String trackingNo) {

    public RegisterExchangeShipmentRequest {
        trackingNo = Delivery.stripTrackingNo(trackingNo);
    }
}

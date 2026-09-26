package com.zslab.mall.delivery.controller.request;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 송장 정정 요청(Track 89-B D-184). carrier는 enum 직접 바인딩(허용 외 값 400)·trackingNo는 앞뒤 공백 제거 후
 * {@link Delivery#TRACKING_NO_PATTERN}으로 검증(D-227). 사유는 감사 로그(DELIVERY·after.reason)에만 남는다(Delivery 컬럼 없음·89-A 선례).
 */
public record AdminDeliveryTrackingCorrectionRequest(
        @NotNull DeliveryCarrier carrier,
        @NotNull(message = Delivery.TRACKING_NO_FORMAT_MESSAGE)
        @Pattern(regexp = Delivery.TRACKING_NO_PATTERN, message = Delivery.TRACKING_NO_FORMAT_MESSAGE) String trackingNo,
        @NotBlank @Size(max = 200) String reason) {

    public AdminDeliveryTrackingCorrectionRequest {
        trackingNo = Delivery.stripTrackingNo(trackingNo);
    }
}

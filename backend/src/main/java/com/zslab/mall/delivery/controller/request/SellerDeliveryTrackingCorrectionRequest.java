package com.zslab.mall.delivery.controller.request;

import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 셀러 송장 정정 요청(Track 90-B-1·{@link AdminDeliveryTrackingCorrectionRequest}와 같은 제약). carrier는 enum 직접 바인딩(허용 외 값 400)·
 * trackingNo {@code @Size(max=100)}은 V1 {@code delivery.tracking_no VARCHAR(100)} 정합. 사유는 감사 로그(DELIVERY·after.reason)에만 남는다.
 */
public record SellerDeliveryTrackingCorrectionRequest(
        @NotNull DeliveryCarrier carrier,
        @NotBlank @Size(max = 100) String trackingNo,
        @NotBlank @Size(max = 200) String reason) {
}

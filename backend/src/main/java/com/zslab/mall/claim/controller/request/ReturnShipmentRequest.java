package com.zslab.mall.claim.controller.request;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 반품 회수 송장 등록 요청 DTO(Track 81-A D-170·R4·구매자·관리자 대행 공용). 형식 검증은 {@code PrepareShipmentRequest}
 * (택배사 enum·앞뒤 공백 제거 후 {@link Delivery#TRACKING_NO_PATTERN}·D-227)와 동일하다.
 * 클레임 유형·상태·소유 검증은 {@code ClaimService.registerReturnShipmentByBuyer}가 담당한다.
 */
public record ReturnShipmentRequest(
        @NotNull DeliveryCarrier carrier,
        @NotNull(message = Delivery.TRACKING_NO_FORMAT_MESSAGE)
        @Pattern(regexp = Delivery.TRACKING_NO_PATTERN, message = Delivery.TRACKING_NO_FORMAT_MESSAGE) String trackingNo) {

    public ReturnShipmentRequest {
        trackingNo = Delivery.stripTrackingNo(trackingNo);
    }
}

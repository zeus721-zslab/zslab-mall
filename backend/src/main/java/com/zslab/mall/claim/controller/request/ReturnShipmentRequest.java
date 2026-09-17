package com.zslab.mall.claim.controller.request;

import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 반품 회수 송장 등록 요청 DTO(Track 81-A D-170·R4). 형식 검증은 {@code PrepareShipmentRequest}(택배사 enum·송장 ≤100)와 동일하다.
 * 클레임 유형·상태·소유 검증은 {@code ClaimService.registerReturnShipmentByBuyer}가 담당한다.
 */
public record ReturnShipmentRequest(
        @NotNull DeliveryCarrier carrier,
        @NotBlank @Size(max = 100) String trackingNo) {
}

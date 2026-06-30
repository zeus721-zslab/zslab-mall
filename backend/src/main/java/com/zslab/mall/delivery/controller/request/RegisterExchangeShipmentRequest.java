package com.zslab.mall.delivery.controller.request;

import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 교환품 출고 등록 요청 DTO(D-99 Q3 α·ClaimRequestRequest 패턴 1:1). 형식 검증만 담당하며 권한·상태 판단은 ClaimService가 한다.
 *
 * <p>carrier는 enum 직접 바인딩으로 형식 검증한다(@ValidEnum 미신설·D-99 Q4 β·Jackson 역직렬화 실패 → 400).
 * trackingNo의 {@code @Size(max=100)}은 V1 {@code delivery.tracking_no VARCHAR(100)} 정합이다(정찰 §14.3).
 */
public record RegisterExchangeShipmentRequest(
        DeliveryCarrier carrier,
        @NotBlank @Size(max = 100) String trackingNo) {
}

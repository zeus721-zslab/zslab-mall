package com.zslab.mall.claim.controller.request;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 반품 검수 요청 DTO(Track 81-A D-170·R5). result 필수. PASS는 restock 필수, FAIL은 rejectReasonCode·reshipCarrier·reshipTrackingNo 필수 —
 * 결과별 조건부 필수는 Bean Validation이 아니라 {@code ClaimService.inspect}가 검증한다(IllegalArgumentException → 400).
 */
public record ClaimInspectRequest(
        @NotNull ClaimInspectionResult result,
        Boolean restock,
        ClaimRejectReasonCode rejectReasonCode,
        @Size(max = Claim.REJECT_MEMO_MAX_LENGTH, message = "memo는 500자 이하여야 합니다.") String memo,
        DeliveryCarrier reshipCarrier,
        @Size(max = 100) String reshipTrackingNo) {
}

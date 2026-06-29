package com.zslab.mall.claim.controller.request;

import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 클레임 요청 DTO(D-41 γ·CreateOrderRequest 패턴 1:1). 형식 검증만 담당하며 도메인 규칙(CANCEL 한정·소유권·CLM-5)은
 * ClaimService가 검증한다(D-50·D-89 Q6·Q8).
 *
 * <p>claimType·reasonCode는 enum 타입 바인딩으로 형식 검증한다(@ValidEnum 미구현·CreateOrderRequest PaymentMethod 패턴 정합·
 * 불일치 값은 Jackson 역직렬화 실패 → 400). reasonDetail은 선택값이다.
 */
public record ClaimRequestRequest(
        @NotBlank
        @Pattern(regexp = "^oit_[0-9A-Z]{26}$",
                message = "orderItemPublicId 형식이 올바르지 않습니다(oit_ + ULID 26자).")
        String orderItemPublicId,
        @NotNull ClaimType claimType,
        @NotNull ClaimReasonCode reasonCode,
        @Size(max = 500, message = "reasonDetail은 500자 이하여야 합니다.") String reasonDetail) {

    /** 헤더 유래 buyerId·서버 시각 requestedAt과 결합해 Service Command로 변환한다(D-41·publicId는 Service에서 해소). */
    public ClaimRequestCommand toCommand(Long buyerId, LocalDateTime requestedAt) {
        return new ClaimRequestCommand(orderItemPublicId, claimType, reasonCode, reasonDetail, buyerId, requestedAt);
    }
}

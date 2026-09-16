package com.zslab.mall.claim.controller.request;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 클레임 거부 요청 DTO(Track 80 D-169). 거부 사유 코드 필수·메모 선택이다.
 *
 * <p>reasonCode는 enum 타입 바인딩으로 형식 검증한다({@code ClaimRequestRequest} 패턴·불일치 값은 400). 유형 적합성
 * (ALREADY_SHIPPED는 CANCEL 전용)은 도메인 검증({@code Claim.reject}) 소관이다.
 */
public record ClaimRejectRequest(
        @NotNull ClaimRejectReasonCode reasonCode,
        @Size(max = Claim.REJECT_MEMO_MAX_LENGTH, message = "memo는 500자 이하여야 합니다.") String memo) {
}

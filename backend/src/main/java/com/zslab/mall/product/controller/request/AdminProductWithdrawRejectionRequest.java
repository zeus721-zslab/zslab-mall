package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 운영자 상품 거부 철회 요청 DTO(Track 101-A). 형식 검증만 담당하며 전이 합법성은 {@code ProductApprovalService}가 판단한다.
 *
 * <p>사유는 필수다 — 불가역이던 거부를 되돌리는 조작이라 "누가 왜 되돌렸는지"가 감사 로그에 남아야 한다. 상한 200자는
 * 관리자 송장 정정 사유({@code AdminDeliveryTrackingCorrectionRequest.reason})와 같은 규약이며, Product 컬럼이 아니라
 * 감사 로그 {@code diff_json}에만 저장된다.
 */
public record AdminProductWithdrawRejectionRequest(
        @NotBlank @Size(max = 200) String reason) {
}

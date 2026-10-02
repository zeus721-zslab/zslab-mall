package com.zslab.mall.claim.controller.request;

/**
 * 클레임 승인 요청 DTO(Track 30·D-115 결정2). 폐기된 교환 차액 환불 금액 단일 필드다.
 *
 * <p>Bean Validation 제약은 두지 않는다: refundAmount는 D-177(같은 가격 옵션만 교환)로 폐기돼 값이 실려오면 부호와 무관하게
 * {@code ClaimService.approve}가 400(MALFORMED_REQUEST)으로 거절한다. NULL·body 부재(Controller {@code required=false})가 정상 승인이다.
 */
public record ClaimApproveRequest(Long refundAmount) {
}

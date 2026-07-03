package com.zslab.mall.claim.controller.request;

/**
 * 클레임 승인 요청 DTO(Track 30·D-115 결정2). EXCHANGE 차액 환불 금액 단일 필드다.
 *
 * <p>Bean Validation을 두지 않는다: refundAmount 음수는 {@code Claim.approve}의 도메인 검증
 * ({@code IllegalArgumentException})이 400으로 처리한다({@code AdminRefundInitiateRequest} 무검증 정합·D-115).
 * NULL은 차액 없는 교환(=기존 동작·Refund 미경유)이며, body 자체가 없어도 승인은 성립한다(Controller에서 required=false).
 */
public record ClaimApproveRequest(Long refundAmount) {
}

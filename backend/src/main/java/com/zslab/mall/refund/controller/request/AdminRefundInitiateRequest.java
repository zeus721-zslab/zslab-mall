package com.zslab.mall.refund.controller.request;

/**
 * 운영자 수동 환불 개시 요청(Track 22·D-106 §5). amount 단일 필드다.
 *
 * <p>Bean Validation을 두지 않는다: amount ≤ 0은 {@code RefundService.initiate}의 도메인 검증(amount &lt; 1 →
 * {@code IllegalArgumentException})이 400(MALFORMED_REQUEST)으로 처리한다({@code AdminInventoryAdjustRequest.quantityDelta}
 * 무검증 정합·D-106 §5).
 */
public record AdminRefundInitiateRequest(long amount) {
}

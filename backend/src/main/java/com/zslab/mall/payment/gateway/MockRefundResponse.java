package com.zslab.mall.payment.gateway;

/**
 * PG 환불 요청 등록 응답(expected-spec §6.3). {@link PaymentGateway#refund} 반환 타입이다.
 *
 * <p>{@code pgRefundId}는 PG가 부여한 환불 거래 식별자로, {@code RefundService.initiate}가 PENDING 환불 행에 부여해
 * 이후 webhook 콜백 매칭 키로 사용한다(RFN-3·expected-spec §6). 최종 환불 성공/실패 확정은 webhook으로 비동기 수신하므로
 * {@code success}는 PG의 요청 접수 결과(요청 단계)를 의미한다. {@link MockPaymentGateway}는 항상 접수 성공(true)을 반환한다.
 *
 * @param pgRefundId    PG 부여 환불 식별자(콜백 매칭 키)
 * @param success       PG 요청 접수 성공 여부(true=접수·false=즉시 거절). Mock은 항상 true
 * @param failureReason 접수 거절 사유. success=true이면 null
 */
public record MockRefundResponse(String pgRefundId, boolean success, String failureReason) {
}

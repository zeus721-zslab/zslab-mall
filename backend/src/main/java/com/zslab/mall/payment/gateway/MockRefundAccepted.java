package com.zslab.mall.payment.gateway;

/**
 * Mock PG가 환불 요청을 접수했음을 알리는 내부 이벤트(Track 80 D-169·C4). 실 PG라면 웹훅으로 돌아올 "환불 완료" 통지를
 * 서버 내부에서 모의하기 위한 트리거다. {@link MockPaymentGateway#refund}만 발행하며 실 PG 도입 시 Mock 패키지와 함께 제거된다.
 *
 * <p>소비처 {@link MockRefundAutoCallbackListener}는 발행 트랜잭션(Refund PENDING + pg_refund_id 저장) 커밋 후에 동작한다 —
 * 커밋 전 콜백은 {@code findByPgRefundId}가 행을 못 찾으므로 즉시 호출은 불가하다.
 *
 * @param pgRefundId Mock이 발급한 PG 환불 식별자(콜백 매칭 키)
 */
public record MockRefundAccepted(String pgRefundId) {
}

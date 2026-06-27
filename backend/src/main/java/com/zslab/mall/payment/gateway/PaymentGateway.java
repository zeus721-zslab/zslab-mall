package com.zslab.mall.payment.gateway;

import com.zslab.mall.payment.enums.PaymentMethod;

/**
 * PG 연동 추상화(D-27). 실 PG 교체 지점이며 본 트랙은 {@link MockPaymentGateway}로 구현한다.
 *
 * <p>책임은 결제 시도를 PG에 등록(결제창 진입 정보 확보)·환불 요청을 PG에 등록하는 것까지다. 결제·환불 결과 통지는
 * PG → Webhook 콜백으로 비동기 수신하므로 본 인터페이스에 두지 않는다(D-27 — 콜백은 Webhook Controller·Service 책임).
 */
public interface PaymentGateway {

    /** PG 식별자({@code pg_provider}에 기록될 값). */
    String provider();

    /**
     * 결제 시도를 PG에 등록하고 결제창(체크아웃) URL을 반환한다. {@code paymentAttemptKey}를 PG metadata로 전달해
     * 콜백 회신 시 행 매핑에 사용한다(D-35).
     *
     * @return 결제창 진입 URL. 프론트 리다이렉트 연동은 본 트랙 범위 밖이다.
     */
    String requestPayment(String paymentAttemptKey, Long amount, PaymentMethod method);

    /**
     * 환불을 PG에 요청 등록하고 PG 부여 환불 식별자를 반환한다(Track 5·expected-spec §6.3). 원 결제의 {@code paymentPgTid}
     * (Payment.pg_tid)를 대상으로 {@code amount}만큼 환불을 요청한다. 최종 환불 성공/실패 확정은 webhook 콜백으로 비동기 수신한다.
     *
     * @param paymentPgTid 원 결제 PG 거래 ID(환불 대상 식별)
     * @param amount       환불 금액(KRW 정수)
     * @return PG 부여 환불 식별자를 담은 응답(pg_refund_id는 콜백 매칭 키)
     * @throws PaymentGatewayException PG 환불 요청 등록 자체가 실패(네트워크·timeout·gateway 예외)한 경우(D-67 FAILED 전이 트리거)
     */
    MockRefundResponse refund(String paymentPgTid, Long amount);
}

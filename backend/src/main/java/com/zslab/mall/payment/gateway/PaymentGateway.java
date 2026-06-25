package com.zslab.mall.payment.gateway;

import com.zslab.mall.payment.enums.PaymentMethod;

/**
 * PG 연동 추상화(D-27). 실 PG 교체 지점이며 본 트랙은 {@link MockPaymentGateway}로 구현한다.
 *
 * <p>책임은 결제 시도를 PG에 등록(결제창 진입 정보 확보)하는 것까지다. 결제 결과 통지는 PG → Webhook 콜백으로 비동기 수신하므로
 * 본 인터페이스에 두지 않는다(D-27 — 콜백은 PaymentWebhookController·PaymentService 책임).
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
}

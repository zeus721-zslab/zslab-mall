package com.zslab.mall.payment.gateway;

import com.github.f4b6a3.ulid.UlidCreator;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.payment.enums.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock PG 구현(Track 3·D-27·Track 5 환불 확장). 실제 외부 PG 호출 없이 결정적 모의 결제창 URL·환불 식별자를 반환한다.
 *
 * <p>결제의 성공·실패·취소는 외부 PG 대신 Webhook Controller로 들어오는 모의 콜백으로 구동한다. 환불 완료는 Track 80(C4)부터
 * 서버 내부에서 자동 발생한다({@link MockRefundAccepted} → {@link MockRefundAutoCallbackListener}).
 * 실 PG 도입 시 본 구현만 교체하고 {@link PaymentGateway} 계약은 유지한다.
 *
 * <p><b>활성 조건(Track 97 D-209)</b>: {@code zslab.payment.gateway=mock}(미지정 시 mock). 실 구현체는 같은 프로퍼티의 다른 값으로
 * 등록해 {@link PaymentGateway} 빈이 항상 1개가 되게 한다. 본 빈이 빠지면 {@link MockRefundAutoCallbackListener}·
 * {@code MockRefundPendingRecoveryScheduler}({@code @ConditionalOnBean(MockPaymentGateway)})도 함께 빠진다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zslab.payment.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    private static final String PROVIDER = "MOCK_PG";
    private static final String MOCK_CHECKOUT_BASE = "https://mock-pg.zslab.local/checkout";

    /** Mock 환불 식별자 prefix(PG-side id·우리 public_id(rfn_)와 별개). */
    private static final String MOCK_REFUND_ID_PREFIX = "mock_rfn_";

    private final TracedEventPublisher eventPublisher;

    public MockPaymentGateway(TracedEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String requestPayment(String paymentAttemptKey, Long amount, PaymentMethod method) {
        // Mock: 외부 호출 대신 attempt_key를 쿼리로 실어 모의 결제창 URL을 합성한다(D-35 metadata 전달 모사).
        String checkoutUrl = MOCK_CHECKOUT_BASE
                + "?attemptKey=" + paymentAttemptKey
                + "&amount=" + amount
                + "&method=" + method;
        log.debug("[MockPaymentGateway] 결제 시도 등록·결제창 URL 발급: attemptKey={}, amount={}, method={}",
                paymentAttemptKey, amount, method);
        return checkoutUrl;
    }

    @Override
    public PgRefundResponse refund(String paymentPgTid, Long amount) {
        // Mock: 외부 호출 대신 PG 부여 환불 식별자를 합성해 항상 접수 성공을 반환한다. 최종 확정은 webhook 콜백 구동.
        String pgRefundId = MOCK_REFUND_ID_PREFIX + UlidCreator.getMonotonicUlid();
        log.debug("[MockPaymentGateway] 환불 요청 등록·pg_refund_id 발급: paymentPgTid={}, amount={}, pgRefundId={}",
                paymentPgTid, amount, pgRefundId);
        // Track 80 C4: 실 PG 웹훅 대신 호출 TX 커밋 후 완료 콜백을 자동 발생시킨다(MockRefundAutoCallbackListener·AFTER_COMMIT).
        eventPublisher.publishEvent(new MockRefundAccepted(pgRefundId));
        return new PgRefundResponse(pgRefundId, true, null);
    }
}

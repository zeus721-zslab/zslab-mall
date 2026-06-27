package com.zslab.mall.payment.gateway;

import com.github.f4b6a3.ulid.UlidCreator;
import com.zslab.mall.payment.enums.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock PG 구현(Track 3·D-27·Track 5 환불 확장). 실제 외부 PG 호출 없이 결정적 모의 결제창 URL·환불 식별자를 반환한다.
 *
 * <p>결제·환불의 성공·실패·취소는 외부 PG 대신 Webhook Controller로 들어오는 모의 콜백으로 구동한다.
 * 실 PG 도입 시 본 구현만 교체하고 {@link PaymentGateway} 계약은 유지한다.
 */
@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final String PROVIDER = "MOCK_PG";
    private static final String MOCK_CHECKOUT_BASE = "https://mock-pg.zslab.local/checkout";

    /** Mock 환불 식별자 prefix(PG-side id·우리 public_id(rfn_)와 별개). */
    private static final String MOCK_REFUND_ID_PREFIX = "mock_rfn_";

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
    public MockRefundResponse refund(String paymentPgTid, Long amount) {
        // Mock: 외부 호출 대신 PG 부여 환불 식별자를 합성해 항상 접수 성공을 반환한다. 최종 확정은 webhook 콜백 구동.
        String pgRefundId = MOCK_REFUND_ID_PREFIX + UlidCreator.getMonotonicUlid();
        log.debug("[MockPaymentGateway] 환불 요청 등록·pg_refund_id 발급: paymentPgTid={}, amount={}, pgRefundId={}",
                paymentPgTid, amount, pgRefundId);
        return new MockRefundResponse(pgRefundId, true, null);
    }
}

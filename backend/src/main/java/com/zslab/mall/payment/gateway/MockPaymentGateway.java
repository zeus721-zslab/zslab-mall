package com.zslab.mall.payment.gateway;

import com.zslab.mall.payment.enums.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock PG 구현(Track 3·D-27). 실제 외부 PG 호출 없이 결정적 모의 결제창 URL을 반환한다.
 *
 * <p>결제 성공·실패·취소는 외부 PG 대신 {@code PaymentWebhookController}로 들어오는 모의 콜백으로 구동한다.
 * 실 PG 도입 시 본 구현만 교체하고 {@link PaymentGateway} 계약은 유지한다.
 */
@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final String PROVIDER = "MOCK_PG";
    private static final String MOCK_CHECKOUT_BASE = "https://mock-pg.zslab.local/checkout";

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
}

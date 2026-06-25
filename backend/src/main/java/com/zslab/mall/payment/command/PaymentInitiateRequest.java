package com.zslab.mall.payment.command;

import com.zslab.mall.payment.enums.PaymentMethod;

/**
 * 결제 시도 명령(D-28). {@code PaymentService.initiate} 입력이다.
 *
 * <p>만료 시각(expires_at)은 본 명령에 담지 않는다 — Service가 호출 시점 기준 기본 TTL(+30분)로 산정한다(D-32).
 * payment_attempt_key도 Service가 발급한다(D-35). 형식 검증은 {@code Payment.create}·Service가 담당한다.
 */
public record PaymentInitiateRequest(
        Long orderId,
        PaymentMethod method,
        Long amount) {
}

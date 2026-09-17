package com.zslab.mall.payment.handler;

import com.zslab.mall.payment.service.PaymentService;
import com.zslab.mall.refund.event.RefundCompleted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 환불 완료 이벤트의 Payment 취소 소비 핸들러(Track 5·expected-spec §7·D-69). {@link RefundCompleted}를 받아
 * 전액 환불 충족 시 Payment를 CANCELLED로 전이한다(D-71).
 *
 * <p><b>실행 시점(D-172 보충·외부 검토 2차)</b>: {@code @EventListener} 동기 소비 — 환불 콜백 TX({@code RefundService.markCompleted})와 같은 TX에서
 * 실행되며 예외는 전파돼 콜백을 롤백한다(Refund COMPLETED·Claim COMPLETED·Payment CANCELLED가 한 TX·실패 시 PG 재전송). DB 전이만 다루므로
 * D-172 분류 (a)에 해당한다 — D-172 본문의 "payment 패키지·범위 밖·미변경"은 오분류였다. Payment 행은 markCompleted가 이미 FOR UPDATE로
 * 잡고 있어(락 순서 Claim → Refund → Payment) 본 핸들러의 UPDATE는 추가 락 대기가 없다. 멱등(이미 CANCELLED·부분환불 no-op)은 유지한다.
 *
 * <p><b>전이 조건 평가 위치</b>: 전액 일치(Σ == Payment.amount)·멱등(이미 CANCELLED)·부분환불 no-op 판단은
 * {@link PaymentService#markCancelled} 내부에서 수행한다(D-71). 본 핸들러는 호출 라우팅만 담당한다.
 */
@Slf4j
@Component
public class PaymentRefundCompletedHandler {

    private final PaymentService paymentService;

    public PaymentRefundCompletedHandler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @EventListener
    public void onRefundCompleted(RefundCompleted event) {
        // markCancelled가 전액 일치·멱등·부분환불 no-op을 내부 평가(D-71)
        paymentService.markCancelled(event.paymentId());
    }
}

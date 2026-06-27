package com.zslab.mall.payment.handler;

import com.zslab.mall.payment.service.PaymentService;
import com.zslab.mall.refund.event.RefundCompleted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 환불 완료 이벤트의 Payment 취소 소비 핸들러(Track 5·expected-spec §7·D-69). {@link RefundCompleted}를 받아
 * 전액 환불 충족 시 Payment를 CANCELLED로 전이한다(D-71).
 *
 * <p><b>실행 시점(D-69·CR-06)</b>: {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 Refund UPDATE 커밋 후 진입한다.
 * AFTER_COMMIT은 원 트랜잭션 완료 후라 기본 전파로는 쓰기가 커밋되지 않으므로 {@code REQUIRES_NEW}로 별도 트랜잭션을 명시한다
 * (D-69 "각자 별도 트랜잭션"·D-75). 핸들러는 자체 멱등성을 보장한다.
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRefundCompleted(RefundCompleted event) {
        // markCancelled가 전액 일치·멱등·부분환불 no-op을 내부 평가(D-71)
        paymentService.markCancelled(event.paymentId());
    }
}

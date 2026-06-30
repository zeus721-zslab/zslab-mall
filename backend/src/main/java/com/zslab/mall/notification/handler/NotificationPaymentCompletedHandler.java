package com.zslab.mall.notification.handler;

import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.payment.event.PaymentCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PaymentCompleted(E2) → NotificationLog 적재 핸들러(Track 12·D-95 Q2·Q3·Q4·Q7). {@link PaymentCompleted}를
 * 소비해 {@link NotificationService#recordPaymentCompleted}로 결제 완료 알림을 PENDING 적재한다.
 *
 * <p><b>동기 핸들러 공존(D-95 Q3)</b>: 동일 이벤트를 {@code payment/handler/OrderEventHandler}가 {@code @EventListener}로
 * 동기 소비(markPaid)한다. 본 핸들러는 {@code AFTER_COMMIT}이라 동기 핸들러 커밋 후 별도 트랜잭션에서 실행돼 자연 분리된다.
 *
 * <p><b>실행 시점(D-95 Q3 α·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}.
 *
 * <p><b>실패 격리(D-95 Q7 α)</b>: 적재 예외는 핸들러 밖으로 전파하지 않으며 structured log 1줄만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPaymentCompletedHandler {

    private final NotificationService notificationService;
    private final EventMetricsRecorder eventMetricsRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(PaymentCompleted event) {
        try {
            notificationService.recordPaymentCompleted(event);
        } catch (RuntimeException exception) {
            log.warn("[Notification] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "PaymentCompleted", "ORDER", event.orderId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
            eventMetricsRecorder.recordFailed(event.getClass().getSimpleName()); // Q4 β′: zslab.event.failed{event} 계측
        }
    }
}

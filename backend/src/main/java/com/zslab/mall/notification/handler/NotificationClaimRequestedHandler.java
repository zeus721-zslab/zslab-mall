package com.zslab.mall.notification.handler;

import com.zslab.mall.claim.event.ClaimRequested;
import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ClaimRequested → SMS NotificationLog 적재 핸들러(Track 80 D-169·C5). {@link ClaimRequested}를 소비해
 * {@link NotificationService#recordClaimRequested}로 구매자 요청 접수 SMS를 적재·발송한다.
 *
 * <p><b>클래스명(D-74·D-95)</b>: {@code claim/handler/ClaimRequestedHandler(품목 *_REQUESTED 전이·동기)}와 동명 충돌을 피하려 {@code Notification} prefix를 붙인다.
 *
 * <p><b>실행 시점(D-95 Q3 α·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW} — 발행
 * 트랜잭션 커밋 후 실행되므로 발송 실패가 클레임 처리 자체를 롤백하지 않는다.
 *
 * <p><b>실패 격리(D-95 Q7 α)</b>: 적재 예외는 핸들러 밖으로 전파하지 않으며 structured log 1줄만 남긴다. 수신번호 부재 등
 * 산정 실패는 Service가 skip + warn으로 처리한다(A1-α).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationClaimRequestedHandler {

    private final NotificationService notificationService;
    private final EventMetricsRecorder eventMetricsRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ClaimRequested event) {
        try {
            notificationService.recordClaimRequested(event);
        } catch (RuntimeException exception) {
            log.warn("[Notification] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "ClaimRequested", "CLAIM", event.claimId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
            eventMetricsRecorder.recordFailed(event.getClass().getSimpleName()); // Q4 β′: zslab.event.failed{event} 계측
        }
    }
}

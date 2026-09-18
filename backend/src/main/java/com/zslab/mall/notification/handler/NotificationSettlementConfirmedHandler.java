package com.zslab.mall.notification.handler;

import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.settlement.event.SettlementConfirmed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * SettlementConfirmed → 셀러 SMS NotificationLog 적재 핸들러(Track 85). {@link SettlementConfirmed}를 소비해
 * {@link NotificationService#recordSettlementConfirmed}로 정산 정상처리 SMS를 적재·발송한다.
 *
 * <p><b>실행 시점(D-95 Q3 α·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW} — 전이(confirm)
 * 트랜잭션 커밋 후 실행되므로 발송 실패가 CONFIRMED 전이를 롤백하지 않는다({@code NotificationClaimRejectedHandler} 동형).
 *
 * <p><b>실패 격리(D-95 Q7 α)</b>: 적재 예외는 핸들러 밖으로 전파하지 않으며 structured log 1줄만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSettlementConfirmedHandler {

    private final NotificationService notificationService;
    private final EventMetricsRecorder eventMetricsRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(SettlementConfirmed event) {
        try {
            notificationService.recordSettlementConfirmed(event);
        } catch (RuntimeException exception) {
            log.warn("[Notification] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "SettlementConfirmed", "SETTLEMENT", event.settlementId(), MDC.get("correlationId"),
                    this.getClass().getSimpleName(), exception);
            eventMetricsRecorder.recordFailed(event.getClass().getSimpleName());
        }
    }
}

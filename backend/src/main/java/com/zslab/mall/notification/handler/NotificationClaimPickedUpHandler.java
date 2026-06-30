package com.zslab.mall.notification.handler;

import com.zslab.mall.claim.event.ClaimPickedUp;
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
 * ClaimPickedUp(E11) → NotificationLog 적재 핸들러(Track 14 PR-1·D-98 Q8·D-95 패턴 1:1). {@link ClaimPickedUp}을 소비해
 * {@link NotificationService#recordClaimPickedUp}로 수거 확인 알림을 PENDING 적재한다.
 *
 * <p><b>클래스명(D-74·D-95)</b>: {@code refund/handler/ClaimPickedUpHandler}(D-98 환불 자동 트리거)와 동명 충돌을
 * 피하려 {@code Notification} prefix를 붙인다. 두 핸들러는 동일 이벤트를 각자 AFTER_COMMIT 별도 트랜잭션에서 소비한다.
 *
 * <p><b>실행 시점(D-95 Q3 α·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}.
 *
 * <p><b>실패 격리(D-95 Q7 α)</b>: 적재 예외는 핸들러 밖으로 전파하지 않으며 structured log 1줄만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationClaimPickedUpHandler {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ClaimPickedUp event) {
        try {
            notificationService.recordClaimPickedUp(event);
        } catch (RuntimeException exception) {
            log.warn("[Notification] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "ClaimPickedUp", "CLAIM", event.claimId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
        }
    }
}

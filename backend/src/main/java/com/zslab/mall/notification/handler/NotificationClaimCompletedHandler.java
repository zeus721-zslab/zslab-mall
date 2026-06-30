package com.zslab.mall.notification.handler;

import com.zslab.mall.claim.event.ClaimCompleted;
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
 * ClaimCompleted(E9) → NotificationLog 적재 핸들러(Track 12·D-95 Q2·Q3·Q4·Q7). {@link ClaimCompleted}를 소비해
 * {@link NotificationService#recordClaimCompleted}로 클레임 완료 알림을 PENDING 적재한다.
 *
 * <p><b>클래스명(D-74·D-95)</b>: {@code claim/handler/ClaimCompletedHandler}(OrderItem 종결 전이)와 동명 충돌을
 * 피하려 {@code Notification} prefix를 붙인다.
 *
 * <p><b>실행 시점(D-95 Q3 α·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로
 * 클레임 종결 커밋 후 별도 트랜잭션에서 적재한다.
 *
 * <p><b>실패 격리(D-95 Q7 α)</b>: 적재 예외는 핸들러 밖으로 전파하지 않으며 structured log 1줄만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationClaimCompletedHandler {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ClaimCompleted event) {
        try {
            notificationService.recordClaimCompleted(event);
        } catch (RuntimeException exception) {
            log.warn("[Notification] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "ClaimCompleted", "CLAIM", event.claimId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
        }
    }
}

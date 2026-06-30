package com.zslab.mall.notification.handler;

import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ClaimApproved → NotificationLog 적재 핸들러(Track 12·D-95 Q2·Q3·Q4·Q7). {@link ClaimApproved}를 소비해
 * {@link NotificationService#recordClaimApproved}로 클레임 승인 알림을 PENDING 적재한다.
 *
 * <p><b>클래스명(D-74·D-95)</b>: {@code refund/handler/ClaimApprovedHandler}(D-94 Refund 자동 트리거)와 동명 충돌을
 * 피하려 {@code Notification} prefix를 붙인다. 두 핸들러는 동일 이벤트를 각자 AFTER_COMMIT 별도 트랜잭션에서 소비한다
 * (Refund initiate + NotificationLog 적재 공존).
 *
 * <p><b>실행 시점(D-95 Q3 α·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}.
 *
 * <p><b>실패 격리(D-95 Q7 α)</b>: 적재 예외는 핸들러 밖으로 전파하지 않으며 structured log 1줄만 남긴다. 산정 실패
 * (recipient/title/content 산정 불가)는 Service가 skip + warn으로 처리한다(A1-α).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationClaimApprovedHandler {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ClaimApproved event) {
        try {
            notificationService.recordClaimApproved(event);
        } catch (RuntimeException exception) {
            log.warn("notification log failed; event={} target_type={} target_id={} action=manual_review",
                    "ClaimApproved", "CLAIM", event.claimId(), exception);
        }
    }
}

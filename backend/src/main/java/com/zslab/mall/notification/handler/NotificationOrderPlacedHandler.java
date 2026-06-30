package com.zslab.mall.notification.handler;

import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.order.event.OrderPlaced;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * OrderPlaced(E1) → NotificationLog 적재 핸들러(Track 12·D-95 Q2·Q3·Q4·Q7). {@link OrderPlaced}를 소비해
 * {@link NotificationService#recordOrderPlaced}로 주문 접수 알림을 PENDING 적재한다.
 *
 * <p><b>위치(D-95 Q2 α)</b>: 반응 도메인(Notification) 패키지에 둔다. D-94 ClaimApprovedHandler 패턴 1:1·
 * notification 의존 역유입 차단.
 *
 * <p><b>실행 시점(D-95 Q3 α·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로
 * 주문 커밋 후 별도 트랜잭션에서 적재한다(domain-events "알림 = 비동기" 정합·원 흐름 비차단).
 *
 * <p><b>실패 격리(D-95 Q7 α)</b>: 적재 예외는 핸들러 밖으로 전파하지 않으며 structured log 1줄만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOrderPlacedHandler {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(OrderPlaced event) {
        try {
            notificationService.recordOrderPlaced(event);
        } catch (RuntimeException exception) {
            log.warn("notification log failed; event={} target_type={} target_id={} action=manual_review",
                    "OrderPlaced", "ORDER", event.orderId(), exception);
        }
    }
}

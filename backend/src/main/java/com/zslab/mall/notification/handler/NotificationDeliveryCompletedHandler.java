package com.zslab.mall.notification.handler;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * DeliveryCompleted(E5) → NotificationLog 적재 핸들러(Track 13·D-97 Q6). {@link DeliveryCompleted}를 소비해
 * {@link NotificationService#recordDeliveryCompleted}로 배송 완료 알림을 PENDING 적재한다.
 *
 * <p><b>클래스명(D-74·D-97)</b>: {@code order/handler/DeliveryCompletedHandler}(OrderItem 동기 전이)와 동명 충돌을
 * 피하려 {@code Notification} prefix를 붙인다.
 *
 * <p><b>실행 시점(D-97 Q6·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로
 * 배송 완료 커밋 후 별도 트랜잭션에서 적재한다.
 *
 * <p><b>실패 격리(D-95 Q7 α)</b>: 적재 예외는 핸들러 밖으로 전파하지 않으며 structured log 1줄만 남긴다.
 *
 * <p><b>교환 배송 분기(D-98 Q5·외부 검토 2차 R1 흡수)</b>: {@code delivery.claimId != null}인 교환 배송 완료는
 * 배송 완료 알림 대신 E9 ClaimCompleted 경로({@code NotificationClaimCompletedHandler})가 처리하므로 본 핸들러는 적재하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryCompletedHandler {

    private final NotificationService notificationService;
    private final DeliveryRepository deliveryRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(DeliveryCompleted event) {
        // D-98 Q5·외부 검토 2차 R1 흡수·교환 배송 완료 알림은 NotificationClaimCompletedHandler가 E9로 처리
        Delivery delivery = deliveryRepository.findById(event.deliveryId()).orElse(null);
        if (delivery != null && delivery.getClaimId() != null) {
            log.debug("[Notification] exchange delivery → claim completed notification delegated: deliveryId={} claimId={}",
                    event.deliveryId(), delivery.getClaimId());
            return;
        }
        try {
            notificationService.recordDeliveryCompleted(event);
        } catch (RuntimeException exception) {
            log.warn("notification log failed; event={} target_type={} target_id={} action=manual_review",
                    "DeliveryCompleted", "DELIVERY", event.deliveryId(), exception);
        }
    }
}

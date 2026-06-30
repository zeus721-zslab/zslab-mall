package com.zslab.mall.delivery.event;

import com.zslab.mall.delivery.enums.DeliveryCarrier;
import java.time.LocalDateTime;

/**
 * 배송 시작 도메인 이벤트(E4·D-30 사실 통지·QB-13 record 패턴·D-97 Q1). Spring ApplicationEvent로 발행한다.
 *
 * <p>발행 시점은 {@code DeliveryService.markShipping}의 save 직후다(D-29 save→publish·no flush). 페이로드는
 * 식별자·택배사·운송장번호·시각만 보유하며 도메인 상태를 복제하지 않는다(D-30).
 *
 * <p><b>소비</b>: {@code order/handler/DeliveryStartedHandler}가 동기({@code @EventListener}·D-97 Q5)로
 * OrderItem을 SHIPPING으로 전이하고 Order.status를 재계산한다. {@code notification/handler/NotificationDeliveryStartedHandler}가
 * 비동기({@code @TransactionalEventListener(AFTER_COMMIT)}·REQUIRES_NEW)로 발송 알림을 NotificationLog에 적재한다.
 */
public record DeliveryStarted(
        Long deliveryId,
        Long orderItemId,
        DeliveryCarrier carrier,
        String trackingNo,
        LocalDateTime occurredAt) {
}

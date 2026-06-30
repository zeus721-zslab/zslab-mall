package com.zslab.mall.delivery.service;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.event.DeliveryStarted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송 Application Service(Track 13·D-97). 발송(markShipping)·배송 완료(markDelivered) 도메인 전이를 수행하고
 * 각 전이 직후 E4 DeliveryStarted·E5 DeliveryCompleted를 발행한다(D-29 save→publish·no flush).
 *
 * <p><b>진입점(D-97 Q3)</b>: actor 비의존 시그니처({@code markShipping(deliveryId, trackingNo)}·{@code markDelivered(deliveryId)}).
 * Controller·endpoint는 본 트랙 OUT-OF-SCOPE이며 판매자/운영자 API는 별도 트랙 이연이다.
 *
 * <p>상태 전이 합법성·DLV-3(shipped_at ≤ delivered_at) 검증은 {@link Delivery} 도메인 메서드가 책임진다(D-97 Q2).
 */
@Slf4j
@Service
@Transactional
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DeliveryService(DeliveryRepository deliveryRepository, ApplicationEventPublisher eventPublisher) {
        this.deliveryRepository = deliveryRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 발송 처리(READY → SHIPPING). 전이 후 save 직후 {@link DeliveryStarted}를 발행한다(D-29).
     *
     * @throws IllegalArgumentException 배송이 없는 경우
     * @throws IllegalStateException    불법 배송 상태 전이 시(Delivery.markShipping 위임)
     */
    public void markShipping(Long deliveryId, String trackingNo) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("배송을 찾을 수 없습니다: deliveryId=" + deliveryId));
        delivery.markShipping(trackingNo, LocalDateTime.now());
        deliveryRepository.save(delivery);
        eventPublisher.publishEvent(new DeliveryStarted(
                delivery.getId(), delivery.getOrderItemId(), delivery.getCarrier(),
                delivery.getTrackingNo(), LocalDateTime.now()));
    }

    /**
     * 배송 완료 처리(SHIPPING → DELIVERED). 전이 후 save 직후 {@link DeliveryCompleted}를 발행한다(D-29).
     *
     * @throws IllegalArgumentException 배송이 없는 경우
     * @throws IllegalStateException    불법 배송 상태 전이 또는 DLV-3 위반 시(Delivery.markDelivered 위임)
     */
    public void markDelivered(Long deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("배송을 찾을 수 없습니다: deliveryId=" + deliveryId));
        delivery.markDelivered(LocalDateTime.now());
        deliveryRepository.save(delivery);
        eventPublisher.publishEvent(new DeliveryCompleted(
                delivery.getId(), delivery.getOrderItemId(), delivery.getDeliveredAt(), LocalDateTime.now()));
    }
}

package com.zslab.mall.delivery.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.event.DeliveryStarted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
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
    private final ClaimRepository claimRepository;
    private final TracedEventPublisher eventPublisher;

    public DeliveryService(DeliveryRepository deliveryRepository, ClaimRepository claimRepository,
            TracedEventPublisher eventPublisher) {
        this.deliveryRepository = deliveryRepository;
        this.claimRepository = claimRepository;
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

    /**
     * 교환품 출고 등록(D-98 Q3·Seller Service 트랙 endpoint 진입점 예정). 단일 트랜잭션에서 (1) Delivery.create·save로
     * id 발급 (2) {@link Claim#attachExchangeDelivery}로 type·orderItemId 불변식 검증 + {@link Delivery#attachExchangeClaim}
     * 연결 (3) {@link Delivery#markShipping}(E4 발행)을 일괄 처리한다.
     *
     * <p>흐름: ClaimApproved(EXCHANGE) → ClaimPickedUp(E11) → 본 메서드 → 배송 완료 시
     * {@code ExchangeDeliveryCompletedHandler}가 OrderItem EXCHANGED·Claim COMPLETED로 종결한다.
     *
     * @param claimId    교환 클레임 id (EXCHANGE·APPROVED·pickedUpAt != null 가정)
     * @param carrier    택배사
     * @param trackingNo 운송장번호 (NOT NULL·{@link Delivery#markShipping} 검증)
     * <p>Q11 멱등 가드: 동일 claimId 재호출 시 {@link ClaimInvalidStateException}을 던진다(422). 첫 출고 등록만 보장하며
     * 재출고 시나리오는 후속 트랙이다(D-99 Q11 α·delivery.claim_id UNIQUE 금지·재출고 허용 박제 정합).
     *
     * @return 생성된 Delivery(SHIPPING·claim_id 연결 완료)
     * @throws ClaimNotFoundException     클레임 미발견
     * @throws ClaimInvalidStateException type != EXCHANGE·orderItemId 불일치(Claim.attachExchangeDelivery 위임)·이중 호출(Q11 가드)
     * @throws IllegalStateException      markShipping 검증 위반(trackingNo·shippedAt null)
     */
    public Delivery registerExchangeShipment(Long claimId, DeliveryCarrier carrier, String trackingNo) {
        deliveryRepository.findByClaimId(claimId).ifPresent(existing -> {
            throw new ClaimInvalidStateException("교환 배송이 이미 등록되었습니다: claimId=" + claimId);
        });

        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));

        Delivery delivery = Delivery.create(claim.getOrderItemId(), carrier);
        deliveryRepository.save(delivery); // delivery.id 발급(attachExchangeDelivery 인자 요건)

        // Aggregate 불변식 검증(Claim 측·D-98 Q13·외부 검토 1차 Q3 흡수)
        claim.attachExchangeDelivery(delivery.getId(), delivery.getOrderItemId());

        delivery.attachExchangeClaim(claimId);
        delivery.markShipping(trackingNo, LocalDateTime.now());
        deliveryRepository.save(delivery);

        eventPublisher.publishEvent(new DeliveryStarted(
                delivery.getId(), delivery.getOrderItemId(), delivery.getCarrier(),
                delivery.getTrackingNo(), LocalDateTime.now()));
        return delivery;
    }

    /**
     * Admin actor의 교환 배송 등록 wrapper.
     * D-92 primitive actor 비의존 원칙·D-93 AdminActorResolver seam 재사용 4회차·
     * primitive registerExchangeShipment 1:1 위임·actor 파라미터 비수신.
     * D-102 §5·D-99 §후속 L5110 carry-over 해소.
     */
    @Transactional
    public Delivery registerExchangeShipmentByAdmin(Long claimId,
                                                    DeliveryCarrier carrier,
                                                    String trackingNo) {
        return registerExchangeShipment(claimId, carrier, trackingNo);
    }

    /**
     * Admin actor의 배송 완료 처리 wrapper.
     * D-92 primitive actor 비의존 원칙·D-93 AdminActorResolver seam 재사용 6회차·
     * primitive markDelivered 1:1 위임·actor 파라미터 비수신.
     * D-102 §5 wrapper 패턴 2회차·D-104 §후속.
     */
    @Transactional
    public void markDeliveredByAdmin(Long deliveryId) {
        markDelivered(deliveryId);
    }
}

package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 교환 배송 완료 이벤트의 Claim·OrderItem 종결 핸들러(D-98 Q5·E5 DeliveryCompleted 비동기 소비).
 *
 * <p>소비 순서(D-98 Q5 박제): OrderItem → EXCHANGED → Claim COMPLETED. 역순 금지.
 *
 * <p>이중 가드(D-98 Q5·외부 검토 1차 Q3 β 흡수·운영 안전성):
 * <ul>
 *   <li>{@code delivery.claimId != null} — 일반 배송 제외(본 핸들러 비대상)
 *   <li>{@code claim.type == EXCHANGE} — 데이터 손상 방어·log.warn + skip(throw 금지·이벤트 적체 회피)
 * </ul>
 *
 * <p>멱등(외부 검토 2차 R3 흡수): OrderItem 이미 EXCHANGED → 자연 skip·Claim 이미 COMPLETED →
 * {@link ClaimService#markCompleted} 멱등 가드(publishEvent 가드 안 위치·R3 실측 정합)가 자연 차단한다.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Transactional(REQUIRES_NEW)} — ClaimPickedUpHandler 패턴 1:1.
 */
@Slf4j
@Component
public class ExchangeDeliveryCompletedHandler {

    private final DeliveryRepository deliveryRepository;
    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;
    private final ClaimService claimService;

    public ExchangeDeliveryCompletedHandler(DeliveryRepository deliveryRepository,
            ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository,
            OrderService orderService,
            ClaimService claimService) {
        this.deliveryRepository = deliveryRepository;
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderService = orderService;
        this.claimService = claimService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(DeliveryCompleted event) {
        Delivery delivery = deliveryRepository.findById(event.deliveryId()).orElse(null);
        if (delivery == null) {
            log.warn("[ExchangeDelivery] DeliveryCompleted 수신·Delivery 미발견: deliveryId={}", event.deliveryId());
            return;
        }
        if (delivery.getClaimId() == null) {
            // 일반 배송 — 본 핸들러 비대상
            return;
        }

        Claim claim = claimRepository.findById(delivery.getClaimId()).orElse(null);
        if (claim == null) {
            log.warn("[ExchangeDelivery] Claim 미발견: claimId={} deliveryId={}",
                    delivery.getClaimId(), event.deliveryId());
            return;
        }
        if (claim.getType() != ClaimType.EXCHANGE) {
            // 데이터 손상 방어·throw 금지·이벤트 적체 회피(외부 검토 1차 Q3 β 흡수)
            log.warn("[ExchangeDelivery] type 불일치·skip: claimId={} type={}", claim.getId(), claim.getType());
            return;
        }

        // 1단계: OrderItem EXCHANGED 전이(D-98 Q5 소비 순서)
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[ExchangeDelivery] OrderItem 미발견: orderItemId={}", event.orderItemId());
            return;
        }
        if (orderItem.getItemStatus() == OrderItemStatus.EXCHANGED) {
            // 멱등(이미 전이됨) — Claim 종결 시도는 계속(markCompleted 멱등 가드가 차단)
            log.info("[ExchangeDelivery] OrderItem 이미 EXCHANGED → 멱등 skip: orderItemId={}", event.orderItemId());
        } else if (!orderItem.getItemStatus().canTransitionTo(OrderItemStatus.EXCHANGED)) {
            log.warn("[ExchangeDelivery] OrderItem 상태={} → EXCHANGED 전이 불가·skip: orderItemId={}",
                    orderItem.getItemStatus(), event.orderItemId());
            return;
        } else {
            orderItem.changeStatus(OrderItemStatus.EXCHANGED);
            Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "OrderItem의 order_id를 해소할 수 없습니다: orderItemId=" + orderItem.getId()));
            orderService.recalculateStatus(orderId);
        }

        // 2단계: Claim 종결(멱등 가드는 ClaimService.markCompleted 자연 흡수·R3 정합)
        claimService.markCompleted(claim.getId());
    }
}

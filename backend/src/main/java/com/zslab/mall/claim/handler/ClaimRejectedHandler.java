package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.repository.ClaimRepository;
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
 * 클레임 거절 이벤트의 OrderItem 스냅샷 원복 핸들러(Track 9 PR-C·D-98 Q7·D-90 Q3 의미 변경). {@link ClaimRejected}를 받아
 * type 무관으로 대상 OrderItem을 요청 시점 상태({@code claim.previousOrderItemStatus} 스냅샷·Q11)로 원복한다.
 *
 * <p><b>스냅샷 기반 원복(D-98 Q7)</b>: 고정 상태 환원이 아니라 요청 시점에 캡처한 OrderItem 상태로 복원한다.
 * CANCEL_REQUESTED → PAID·PREPARING, RETURN_REQUESTED → SHIPPING·DELIVERED, EXCHANGE_REQUESTED → DELIVERED를 허용하는
 * 신규 전이 매트릭스(D-98 Q7)와 정합한다. D-90 Q3의 PAID 고정 환원(claim-lock release)은 본 결정으로 의미 변경되었다.
 *
 * <p><b>실행 시점·멱등</b>: ClaimRequestedHandler와 동일(AFTER_COMMIT·REQUIRES_NEW). 이미 스냅샷 상태로 복원됐거나
 * 대상 *_REQUESTED 상태가 아니면 no-op이다.
 */
@Slf4j
@Component
public class ClaimRejectedHandler {

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;

    public ClaimRejectedHandler(ClaimRepository claimRepository, OrderItemRepository orderItemRepository,
            OrderService orderService) {
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderService = orderService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onClaimRejected(ClaimRejected event) {
        Claim claim = claimRepository.findById(event.claimId()).orElse(null);
        if (claim == null) {
            log.warn("[Claim] ClaimRejected 소비·클레임 미발견: claimId={}", event.claimId());
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Claim] ClaimRejected 소비·주문 품목 미발견: orderItemId={}", event.orderItemId());
            return;
        }
        OrderItemStatus requestedStatus = switch (event.claimType()) {
            case CANCEL -> OrderItemStatus.CANCEL_REQUESTED;
            case RETURN -> OrderItemStatus.RETURN_REQUESTED;
            case EXCHANGE -> OrderItemStatus.EXCHANGE_REQUESTED;
        };
        if (orderItem.getItemStatus() != requestedStatus) {
            // 멱등/비대상(이미 스냅샷 상태로 복원됐거나 요청 상태가 아님) — 안전 차단
            log.info("[Claim] OrderItem 상태={} → 스냅샷 원복 비대상·건너뜀: orderItemId={}",
                    orderItem.getItemStatus(), event.orderItemId());
            return;
        }
        OrderItemStatus snapshot = claim.getPreviousOrderItemStatus();
        orderItem.changeStatus(snapshot);
        Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem의 order_id를 해소할 수 없습니다: orderItemId=" + orderItem.getId()));
        orderService.recalculateStatus(orderId);
    }
}

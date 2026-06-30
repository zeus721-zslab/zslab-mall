package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.event.ClaimCompleted;
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
 * 클레임 종결 이벤트의 OrderItem 종결 핸들러(Track 9 PR-C·D-90 Q4·D-98 Q4). {@link ClaimCompleted}를 받아 클레임 type별
 * 대상 OrderItem을 *_REQUESTED → 종결 상태로 전이하고 Order.status를 재계산한다(state-machine §3 진입: Claim.COMPLETED).
 *
 * <p><b>중첩 AFTER_COMMIT(D-90 Q1·검증 의무)</b>: 발행처 {@code ClaimService.markCompleted}는
 * {@code ClaimRefundCompletedHandler}(AFTER_COMMIT·REQUIRES_NEW) 내부에서 호출된다. 그 REQUIRES_NEW 트랜잭션에서 발행된
 * {@link ClaimCompleted}는 해당 트랜잭션 커밋 후 본 핸들러를 발화한다(AFTER_COMMIT 리스너 내부 신규 트랜잭션 동기화·Spring 표준).
 *
 * <p><b>type 분기·멱등(D-98 Q4)</b>: CANCEL → CANCELLED·RETURN → RETURNED·EXCHANGE → EXCHANGED. 이미 종결 상태이거나
 * 대상 *_REQUESTED 상태가 아니면 no-op이다.
 */
@Slf4j
@Component
public class ClaimCompletedHandler {

    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;

    public ClaimCompletedHandler(OrderItemRepository orderItemRepository, OrderService orderService) {
        this.orderItemRepository = orderItemRepository;
        this.orderService = orderService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onClaimCompleted(ClaimCompleted event) {
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Claim] ClaimCompleted 소비·주문 품목 미발견: orderItemId={}", event.orderItemId());
            return;
        }
        OrderItemStatus requestedStatus = switch (event.claimType()) {
            case CANCEL -> OrderItemStatus.CANCEL_REQUESTED;
            case RETURN -> OrderItemStatus.RETURN_REQUESTED;
            case EXCHANGE -> OrderItemStatus.EXCHANGE_REQUESTED;
        };
        OrderItemStatus terminalStatus = switch (event.claimType()) {
            case CANCEL -> OrderItemStatus.CANCELLED;
            case RETURN -> OrderItemStatus.RETURNED;
            case EXCHANGE -> OrderItemStatus.EXCHANGED;
        };
        if (orderItem.getItemStatus() != requestedStatus) {
            // 멱등/비대상(이미 종결됐거나 요청 상태가 아님) — 안전 차단
            log.info("[Claim] OrderItem 상태={} → {} 종결 비대상·건너뜀: orderItemId={}",
                    orderItem.getItemStatus(), terminalStatus, event.orderItemId());
            return;
        }
        orderItem.changeStatus(terminalStatus);
        Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem의 order_id를 해소할 수 없습니다: orderItemId=" + orderItem.getId()));
        orderService.recalculateStatus(orderId);
    }
}

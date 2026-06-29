package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.enums.ClaimType;
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
 * 클레임 종결 이벤트의 OrderItem 종결 핸들러(Track 9 PR-C·D-90 Q4). {@link ClaimCompleted}를 받아 CANCEL 클레임 대상
 * OrderItem을 CANCEL_REQUESTED → CANCELLED로 종결하고 Order.status를 재계산한다(state-machine §3 진입: Claim(CANCEL).COMPLETED).
 *
 * <p><b>중첩 AFTER_COMMIT(D-90 Q1·검증 의무)</b>: 발행처 {@code ClaimService.markCompleted}는
 * {@code ClaimRefundCompletedHandler}(AFTER_COMMIT·REQUIRES_NEW) 내부에서 호출된다. 그 REQUIRES_NEW 트랜잭션에서 발행된
 * {@link ClaimCompleted}는 해당 트랜잭션 커밋 후 본 핸들러를 발화한다(AFTER_COMMIT 리스너 내부 신규 트랜잭션 동기화·Spring 표준).
 *
 * <p><b>type 분기·멱등</b>: CANCEL 한정(RETURN·EXCHANGE는 Track 11). 이미 CANCELLED면 no-op.
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
        if (event.claimType() != ClaimType.CANCEL) {
            log.info("[Claim] ClaimCompleted 수신·type={} → 본 트랙 미처리: claimId={}", event.claimType(), event.claimId());
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Claim] ClaimCompleted 소비·주문 품목 미발견: orderItemId={}", event.orderItemId());
            return;
        }
        if (orderItem.getItemStatus() != OrderItemStatus.CANCEL_REQUESTED) {
            // 멱등/비대상(이미 CANCELLED거나 취소 요청 상태가 아님) — 안전 차단
            log.info("[Claim] OrderItem 상태={} → CANCELLED 종결 비대상·건너뜀: orderItemId={}",
                    orderItem.getItemStatus(), event.orderItemId());
            return;
        }
        orderItem.changeStatus(OrderItemStatus.CANCELLED);
        Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem의 order_id를 해소할 수 없습니다: orderItemId=" + orderItem.getId()));
        orderService.recalculateStatus(orderId);
    }
}

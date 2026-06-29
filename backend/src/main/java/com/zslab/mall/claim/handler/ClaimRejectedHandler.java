package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimRejected;
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
 * 클레임 거절 이벤트의 OrderItem claim-lock release 핸들러(Track 9 PR-C·D-90 Q3 α-1). {@link ClaimRejected}를 받아
 * CANCEL 클레임 대상 OrderItem을 CANCEL_REQUESTED → PAID로 환원해 재요청을 허용한다(claim-lock release).
 *
 * <p><b>claim-lock release 의미(D-90 Q3)</b>: 과거 상태 복원이 아니라 Claim 재진입 가능 상태 복구다. PREPARING 직접
 * 복원은 직전 상태 정보 부재로 미지원하며, 판매자는 PAID → PREPARING 정상 재전이로 흐름을 재개한다. PAID 환원으로
 * {@code canTransitionTo(CANCEL_REQUESTED)}가 다시 true가 되어 CLM-2(REJECTED 재요청 = 새 Claim 행)가 실효된다.
 *
 * <p><b>실행 시점·type 분기</b>: ClaimRequestedHandler와 동일(AFTER_COMMIT·REQUIRES_NEW·CANCEL 한정·멱등).
 */
@Slf4j
@Component
public class ClaimRejectedHandler {

    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;

    public ClaimRejectedHandler(OrderItemRepository orderItemRepository, OrderService orderService) {
        this.orderItemRepository = orderItemRepository;
        this.orderService = orderService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onClaimRejected(ClaimRejected event) {
        if (event.claimType() != ClaimType.CANCEL) {
            log.info("[Claim] ClaimRejected 수신·type={} → 본 트랙 미처리: claimId={}", event.claimType(), event.claimId());
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Claim] ClaimRejected 소비·주문 품목 미발견: orderItemId={}", event.orderItemId());
            return;
        }
        if (orderItem.getItemStatus() != OrderItemStatus.CANCEL_REQUESTED) {
            // 멱등/비대상(이미 환원됐거나 취소 요청 상태가 아님) — 안전 차단
            log.info("[Claim] OrderItem 상태={} → claim-lock release 비대상·건너뜀: orderItemId={}",
                    orderItem.getItemStatus(), event.orderItemId());
            return;
        }
        orderItem.changeStatus(OrderItemStatus.PAID);
        Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem의 order_id를 해소할 수 없습니다: orderItemId=" + orderItem.getId()));
        orderService.recalculateStatus(orderId);
    }
}

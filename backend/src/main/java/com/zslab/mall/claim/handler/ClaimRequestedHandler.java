package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimRequested;
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
 * 클레임 요청 이벤트의 OrderItem 동기화 핸들러(Track 9 PR-C·D-90 Q1·D-88 Q6). {@link ClaimRequested}를 받아
 * CANCEL 클레임 대상 OrderItem을 CANCEL_REQUESTED로 전이하고 Order.status를 재계산한다.
 *
 * <p><b>실행 시점(D-90 Q1·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로
 * Claim INSERT 커밋 후 별도 트랜잭션에서 외부 Aggregate(Order)를 갱신한다(D-01 Aggregate 간 이벤트 경유). 부분 실패가
 * 허용되며(재처리 가능) 핸들러는 자체 멱등성을 보장한다(이미 CANCEL_REQUESTED면 no-op·ClaimRefundCompletedHandler 패턴).
 *
 * <p><b>type 분기</b>: 본 트랙은 CANCEL만 처리한다(Q6 CANCEL 한정). RETURN·EXCHANGE는 Track 11 소관이다.
 */
@Slf4j
@Component
public class ClaimRequestedHandler {

    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;

    public ClaimRequestedHandler(OrderItemRepository orderItemRepository, OrderService orderService) {
        this.orderItemRepository = orderItemRepository;
        this.orderService = orderService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onClaimRequested(ClaimRequested event) {
        if (event.claimType() != ClaimType.CANCEL) {
            // RETURN·EXCHANGE는 본 트랙 미처리(Track 11)
            log.info("[Claim] ClaimRequested 수신·type={} → 본 트랙 미처리: claimId={}", event.claimType(), event.claimId());
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Claim] ClaimRequested 소비·주문 품목 미발견: orderItemId={}", event.orderItemId());
            return;
        }
        if (orderItem.getItemStatus() == OrderItemStatus.CANCEL_REQUESTED) {
            // 멱등(이미 전이됨) — 리스너 재처리 안전 차단
            log.info("[Claim] OrderItem 이미 CANCEL_REQUESTED → 전이 건너뜀: orderItemId={}", event.orderItemId());
            return;
        }
        if (!orderItem.getItemStatus().canTransitionTo(OrderItemStatus.CANCEL_REQUESTED)) {
            // 취소 요청 불가 상태(예: 이미 배송 단계) — 데이터 정합 경고 후 차단
            log.warn("[Claim] OrderItem 상태={} → CANCEL_REQUESTED 전이 불가·건너뜀: orderItemId={}",
                    orderItem.getItemStatus(), event.orderItemId());
            return;
        }
        orderItem.changeStatus(OrderItemStatus.CANCEL_REQUESTED);
        Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem의 order_id를 해소할 수 없습니다: orderItemId=" + orderItem.getId()));
        orderService.recalculateStatus(orderId);
    }
}

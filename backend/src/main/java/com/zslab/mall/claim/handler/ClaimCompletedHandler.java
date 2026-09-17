package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 클레임 종결 이벤트의 OrderItem 종결 핸들러(Track 9 PR-C·D-90 Q4·D-98 Q4). {@link ClaimCompleted}를 받아 클레임 type별
 * 대상 OrderItem을 *_REQUESTED → 종결 상태로 전이하고 Order.status를 재계산한다(state-machine §3 진입: Claim.COMPLETED).
 *
 * <p><b>실행 시점(D-172·외부 검토 A 동기화)</b>: {@code @EventListener} 동기 소비 — 발행 트랜잭션과 같은 TX에서 실행되며 예외는 그대로
 * 전파돼 발행 TX(클레임 전이·환불 콜백 등)를 함께 롤백한다(구 AFTER_COMMIT + REQUIRES_NEW + skip 폐기·후속 처리 유실 방지). 대상 행 미발견은
 * 데이터 불일치라 {@link IllegalStateException}으로 전파하고, 이미 목표 상태인 경우만 멱등 no-op이다.
 * 발행처 {@code ClaimService.markCompleted}는 환불 콜백 TX({@code ClaimRefundCompletedHandler} 동기) 안에서 호출되므로 Refund COMPLETED·
 * Claim COMPLETED·품목 종결·재고 복구가 한 트랜잭션으로 묶인다(실패 시 콜백 422 → PG 재전송).
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

    @EventListener
    public void onClaimCompleted(ClaimCompleted event) {
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "ClaimCompleted 소비·주문 품목 미발견: orderItemId=" + event.orderItemId()));
        OrderItemStatus requestedStatus = switch (event.claimType()) {
            case CANCEL -> OrderItemStatus.CANCEL_REQUESTED;
            case RETURN -> OrderItemStatus.RETURN_REQUESTED;
            case EXCHANGE -> OrderItemStatus.EXCHANGE_REQUESTED;
        };
        OrderItemStatus terminalStatus = switch (event.claimType()) {
            case CANCEL -> OrderItemStatus.CANCELLED;
            case RETURN -> OrderItemStatus.RETURNED;
            // Track 83 D-177 결정 1(α): 교환품 배송완료 후 품목은 DELIVERED로 복귀(구매확정 타이머 재시작·반품 허용). EXCHANGED 미사용.
            case EXCHANGE -> OrderItemStatus.DELIVERED;
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

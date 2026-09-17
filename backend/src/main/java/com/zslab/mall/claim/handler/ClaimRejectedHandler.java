package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 클레임 거절 이벤트의 OrderItem 스냅샷 원복 핸들러(Track 9 PR-C·D-98 Q7·D-90 Q3 의미 변경). {@link ClaimRejected}를 받아
 * type 무관으로 대상 OrderItem을 요청 시점 상태({@code claim.previousOrderItemStatus} 스냅샷·Q11)로 원복한다.
 *
 * <p><b>스냅샷 기반 원복(D-98 Q7)</b>: 고정 상태 환원이 아니라 요청 시점에 캡처한 OrderItem 상태로 복원한다.
 * CANCEL_REQUESTED → PAID·PREPARING, RETURN_REQUESTED → SHIPPING·DELIVERED, EXCHANGE_REQUESTED → DELIVERED를 허용하는
 * 신규 전이 매트릭스(D-98 Q7)와 정합한다. D-90 Q3의 PAID 고정 환원(claim-lock release)은 본 결정으로 의미 변경되었다.
 *
 * <p><b>실행 시점(D-172·외부 검토 A 동기화)</b>: {@code @EventListener} 동기 소비 — 발행 트랜잭션과 같은 TX에서 실행되며 예외는 그대로
 * 전파돼 발행 TX(클레임 전이·환불 콜백 등)를 함께 롤백한다(구 AFTER_COMMIT + REQUIRES_NEW + skip 폐기·후속 처리 유실 방지). 대상 행 미발견은
 * 데이터 불일치라 {@link IllegalStateException}으로 전파하고, 이미 목표 상태인 경우만 멱등 no-op이다.
 * 검수 불합격(FAIL) 경로는 {@code ClaimService.inspect} TX 안에서 재발송 Delivery 등록과 함께 원복되므로 원복 실패는 검수 요청 실패다.
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

    @EventListener
    public void onClaimRejected(ClaimRejected event) {
        Claim claim = claimRepository.findById(event.claimId())
                .orElseThrow(() -> new IllegalStateException("ClaimRejected 소비·클레임 미발견: claimId=" + event.claimId()));
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "ClaimRejected 소비·주문 품목 미발견: orderItemId=" + event.orderItemId()));
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

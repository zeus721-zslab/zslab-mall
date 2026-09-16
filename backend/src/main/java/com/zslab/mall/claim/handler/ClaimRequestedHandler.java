package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.event.ClaimRequested;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

/**
 * 클레임 요청 이벤트의 OrderItem 동기화 핸들러(Track 9 PR-C·D-90 Q1·D-88 Q6·D-98 Q4·Track 79 D-168 동기 전환). {@link ClaimRequested}를
 * 받아 클레임 type별 대응 OrderItem을 *_REQUESTED로 전이하고 Order.status를 재계산한다.
 *
 * <p><b>실행 시점(Track 79 D-168·D)</b>: {@code @EventListener} 동기 소비 — 발행 트랜잭션({@code ClaimService.request})과 같은
 * TX에서 실행된다. {@code ClaimService}가 품목을 {@code FOR UPDATE}로 잠근 뒤 CLM-5·전이 가능성을 검증하고 Claim을 INSERT하므로,
 * 본 핸들러의 전이 실패(불법 전이 {@link IllegalStateException})는 Claim INSERT까지 함께 롤백된다 — 같은 품목 동시 요청은 락 직렬화
 * + 전이 검증으로 1건만 성공한다(구 AFTER_COMMIT + REQUIRES_NEW + skip 폐기). 이미 *_REQUESTED면 no-op(멱등)이다.
 *
 * <p><b>type 분기(D-98 Q4)</b>: CANCEL → CANCEL_REQUESTED·RETURN → RETURN_REQUESTED·EXCHANGE → EXCHANGE_REQUESTED.
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

    @EventListener
    public void onClaimRequested(ClaimRequested event) {
        OrderItemStatus targetStatus = switch (event.claimType()) {
            case CANCEL -> OrderItemStatus.CANCEL_REQUESTED;
            case RETURN -> OrderItemStatus.RETURN_REQUESTED;
            case EXCHANGE -> OrderItemStatus.EXCHANGE_REQUESTED;
        };
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "ClaimRequested 소비·주문 품목 미발견: orderItemId=" + event.orderItemId()));
        if (orderItem.getItemStatus() == targetStatus) {
            // 멱등(이미 전이됨) — 재처리 안전 차단
            log.info("[Claim] OrderItem 이미 {} → 전이 건너뜀: orderItemId={}", targetStatus, event.orderItemId());
            return;
        }
        orderItem.changeStatus(targetStatus);   // 불법 전이는 IllegalStateException → 요청 TX(Claim INSERT 포함) 롤백
        Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem의 order_id를 해소할 수 없습니다: orderItemId=" + orderItem.getId()));
        orderService.recalculateStatus(orderId);
    }
}

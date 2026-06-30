package com.zslab.mall.order.handler;

import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 배송 완료 이벤트의 OrderItem 동기화 핸들러(Track 13·D-97 Q4·Q5). {@link DeliveryCompleted}를 받아 대상 OrderItem을
 * DELIVERED로 전이하고 Order.status를 재계산한다(state-machine §3·§6.1 진입: Delivery DELIVERED).
 *
 * <p><b>실행 시점(D-97 Q5·D-29)</b>: {@code @EventListener}로 동기 소비하며 발행자({@code DeliveryService.markDelivered})와
 * 같은 트랜잭션에서 실행된다. 본 핸들러가 실패하면 Delivery 상태 전이까지 함께 롤백된다(OrderItem 상태 = 동기·domain-events §2).
 *
 * <p><b>멱등(D-97 Q8)</b>: OrderItem이 이미 DELIVERED이면 no-op·전이 불가 상태면 {@link OrderItemStatus#canTransitionTo}
 * 가드로 흡수한다(별도 멱등 저장소 미도입). {@code DeliveryStartedHandler} 패턴 1:1.
 */
@Slf4j
@Component
public class DeliveryCompletedHandler {

    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;

    public DeliveryCompletedHandler(OrderItemRepository orderItemRepository, OrderService orderService) {
        this.orderItemRepository = orderItemRepository;
        this.orderService = orderService;
    }

    @EventListener
    public void onDeliveryCompleted(DeliveryCompleted event) {
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Delivery] DeliveryCompleted 소비·주문 품목 미발견: orderItemId={}", event.orderItemId());
            return;
        }
        if (orderItem.getItemStatus() == OrderItemStatus.DELIVERED) {
            // 멱등(이미 전이됨) — 리스너 재처리 안전 차단
            log.info("[Delivery] OrderItem 이미 DELIVERED → 전이 건너뜀: orderItemId={}", event.orderItemId());
            return;
        }
        if (!orderItem.getItemStatus().canTransitionTo(OrderItemStatus.DELIVERED)) {
            // 배송 완료 전이 불가 상태 — 데이터 정합 경고 후 차단
            log.warn("[Delivery] OrderItem 상태={} → DELIVERED 전이 불가·건너뜀: orderItemId={}",
                    orderItem.getItemStatus(), event.orderItemId());
            return;
        }
        orderItem.changeStatus(OrderItemStatus.DELIVERED);
        Long orderId = orderItemRepository.findOrderIdById(orderItem.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OrderItem의 order_id를 해소할 수 없습니다: orderItemId=" + orderItem.getId()));
        orderService.recalculateStatus(orderId);
    }
}

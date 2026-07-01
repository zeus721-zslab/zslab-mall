package com.zslab.mall.inventory.handler;

import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderItemRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * OrderPlaced(E1) → Inventory 예약 핸들러(Track 17 PR-B·D-101 §3). {@link OrderPlaced}를 소비해 orderId로 OrderItem을
 * 재조회한 뒤(D-30 사실 통지·payload 무복제) 각 품목에 대해 {@link InventoryService#reserve}로 재고를 예약한다.
 *
 * <p><b>실행 시점(D-101 §3·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로 주문 커밋 후
 * 별도 트랜잭션에서 예약한다.
 *
 * <p><b>이중 방어(D-100 Q1 γ·D-101 §6)</b>: 1차 핸들러 가드 = OrderItem.item_status != ORDERED면 skip(멱등),
 * 2차 도메인 가드 = reserve INV-1(oversell) 위반 시 InventoryInvariantViolationException throw.
 *
 * <p><b>실패 격리(D-100 Q6 β·Q4 β′)</b>: 예약 실패는 6 표준키 structured log 1줄 + zslab.event.failed 계측 후 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryOrderPlacedHandler {

    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;
    private final EventMetricsRecorder eventMetricsRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(OrderPlaced event) {
        try {
            List<OrderItem> items = orderItemRepository.findByOrderId(event.orderId());
            for (OrderItem item : items) {
                if (item.getItemStatus() != OrderItemStatus.ORDERED) {
                    // 1차 가드(멱등): 이미 결제/취소 등으로 ORDERED가 아니면 예약 비대상
                    log.info("[Inventory] event=OrderPlaced target_id={} action=skip reason=item_status={}",
                            item.getId(), item.getItemStatus());
                    continue;
                }
                inventoryService.reserve(item.getVariantId(), item.getQuantity());
                log.info("[Inventory] event=OrderPlaced target_id={} action=reserve variant_id={} qty={}",
                        item.getId(), item.getVariantId(), item.getQuantity());
            }
        } catch (RuntimeException exception) {
            log.warn("[Inventory] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "OrderPlaced", "ORDER", event.orderId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
            eventMetricsRecorder.recordFailed(event.getClass().getSimpleName()); // Q4 β′: zslab.event.failed{event}
        }
    }
}

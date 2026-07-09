package com.zslab.mall.inventory.handler;

import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.event.OrderCancelled;
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
 * OrderCancelled(D-153 Phase 1) → Inventory 예약 해제 핸들러. {@link OrderCancelled}를 소비해 orderId로 OrderItem을
 * 재조회한 뒤 각 품목의 예약을 {@link InventoryService#release}로 해제한다({@link InventoryPaymentFailedHandler}
 * 재고 해제 원형 복제·구독 이벤트만 OrderCancelled로 교체).
 *
 * <p><b>실행 시점(D-101 §3·D-75 정합)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로
 * 자동취소 커밋 후 별도 트랜잭션에서 해제한다.
 *
 * <p><b>이중 방어(D-100 Q1 γ·D-101 §6·§4 갱신)</b>: 1차 핸들러 가드 = {@link InventoryRepository#findByVariantId}
 * read-only 조회로 잔여 reserved == 0이면 skip(멱등), 2차 도메인 가드 = release INV-3(예약 초과 해제) 위반 시
 * InventoryInvariantViolationException throw.
 *
 * <p><b>실패 격리(D-100 Q6 β·Q4 β′)</b>: 해제 실패는 6 표준키 structured log 1줄 + zslab.event.failed 계측 후 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryOrderCancelledHandler {

    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;
    private final EventMetricsRecorder eventMetricsRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(OrderCancelled event) {
        try {
            List<OrderItem> items = orderItemRepository.findByOrderId(event.orderId());
            for (OrderItem item : items) {
                // 1차 가드(멱등·read-only·§4 갱신 2 예외): variant 잔여 reserved가 0이면 해제할 예약 없음 → skip
                Inventory inventory = inventoryRepository.findByVariantId(item.getVariantId()).orElse(null);
                if (inventory == null || inventory.getQuantityReserved() == 0) {
                    log.info("[Inventory] event=OrderCancelled target_id={} action=skip reason=reserved_zero variant_id={}",
                            item.getId(), item.getVariantId());
                    continue;
                }
                inventoryService.release(item.getVariantId(), item.getQuantity());
                log.info("[Inventory] event=OrderCancelled target_id={} action=release variant_id={} qty={}",
                        item.getId(), item.getVariantId(), item.getQuantity());
            }
        } catch (RuntimeException exception) {
            log.warn("[Inventory] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "OrderCancelled", "ORDER", event.orderId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
            eventMetricsRecorder.recordFailed(event.getClass().getSimpleName()); // Q4 β′: zslab.event.failed{event}
        }
    }
}

package com.zslab.mall.inventory.handler;

import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.payment.event.PaymentCompleted;
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
 * PaymentCompleted(E2) → Inventory 예약 확정(차감) 핸들러(Track 17 PR-B·D-101 §3). {@link PaymentCompleted}를 소비해
 * orderId로 OrderItem을 재조회한 뒤 각 품목에 대해 {@link InventoryService#commitReservation}으로 예약분을 실물 차감한다.
 *
 * <p><b>실행 시점(D-101 §3·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}. 동기
 * {@code OrderEventHandler}(markPaid)가 발행 트랜잭션에서 OrderItem을 PAID로 전이한 뒤 커밋되므로, 본 핸들러 실행 시점엔
 * 이미 PAID다(그래서 item_status 기반 1차 가드는 부적합).
 *
 * <p><b>멱등(D-101 §6 갱신·A′)</b>: 1차 핸들러 가드 없음. 재전달 방어는 PAY-3b UNIQUE(PG 콜백 중복 차단) + D-100 Q2
 * at-most-once 인메모리 publisher + commitReservation INV-3 backstop 이중화로 충족한다(인메모리 publisher 환경 이벤트 재전달 불가).
 * 2차 도메인 가드 = commitReservation INV-3(예약 부족)·INV-4(실물 부족) 위반 시 throw.
 *
 * <p><b>실패 격리(D-100 Q6 β·Q4 β′)</b>: 차감 실패는 6 표준키 structured log 1줄 + zslab.event.failed 계측 후 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryPaymentCompletedHandler {

    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;
    private final EventMetricsRecorder eventMetricsRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(PaymentCompleted event) {
        try {
            List<OrderItem> items = orderItemRepository.findByOrderId(event.orderId());
            for (OrderItem item : items) {
                inventoryService.commitReservation(item.getVariantId(), item.getQuantity(), "order", event.orderId());
                log.info("[Inventory] event=PaymentCompleted target_id={} action=commit variant_id={} qty={}",
                        item.getId(), item.getVariantId(), item.getQuantity());
            }
        } catch (RuntimeException exception) {
            log.warn("[Inventory] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "PaymentCompleted", "ORDER", event.orderId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
            eventMetricsRecorder.recordFailed(event.getClass().getSimpleName()); // Q4 β′: zslab.event.failed{event}
        }
    }
}

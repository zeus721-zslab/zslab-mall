package com.zslab.mall.inventory.handler;

import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.payment.event.PaymentCompleted;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * PaymentCompleted(E2) → Inventory 예약 확정(차감) 핸들러(Track 17 D-101 §3·Track 78 D-167 동기 전환). {@link PaymentCompleted}를
 * 소비해 orderId로 OrderItem을 재조회한 뒤 각 품목에 대해 {@link InventoryService#commitReservation}으로 예약분을 실물 차감한다.
 *
 * <p><b>실행 시점(Track 78 D-167·R1)</b>: {@code @EventListener} 동기 소비 — 발행 트랜잭션({@code PaymentService.handleCallback})
 * 안에서 {@code OrderEventHandler}(markPaid)와 함께 실행된다. 차감 실패(예약 부족 INV-3·실물 부족 INV-4·Inventory 미존재)는
 * 예외를 그대로 전파해 결제 완료 자체를 롤백한다 — "PAID인데 재고 미차감" 상태를 원천 차단한다(구 AFTER_COMMIT + 실패 흡수 폐기).
 *
 * <p><b>락 순서</b>: 다중 품목은 variant id 오름차순으로 {@code SELECT ... FOR UPDATE}를 획득해 동시 결제 간 데드락을 방지한다.
 *
 * <p><b>멱등(D-101 §6 갱신·A′)</b>: 1차 핸들러 가드 없음. 재전달 방어는 PAY-3b UNIQUE(PG 콜백 중복 차단) + Payment PAID 멱등 NO-OP
 * (재발행 없음)로 충족하며, commitReservation INV-3이 backstop이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryPaymentCompletedHandler {

    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;

    @EventListener
    public void handle(PaymentCompleted event) {
        List<OrderItem> items = orderItemRepository.findByOrderId(event.orderId()).stream()
                .sorted(Comparator.comparing(OrderItem::getVariantId))
                .toList();
        for (OrderItem item : items) {
            inventoryService.commitReservation(item.getVariantId(), item.getQuantity(), "order", event.orderId());
            log.info("[Inventory] event=PaymentCompleted target_id={} action=commit variant_id={} qty={}",
                    item.getId(), item.getVariantId(), item.getQuantity());
        }
    }
}

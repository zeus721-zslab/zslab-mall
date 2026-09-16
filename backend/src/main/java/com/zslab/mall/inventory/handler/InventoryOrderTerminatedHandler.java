package com.zslab.mall.inventory.handler;

import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.event.OrderTerminated;
import com.zslab.mall.order.repository.OrderItemRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OrderTerminated(FE-12c·D-153 레벨3) → Inventory 예약 해제 핸들러(Track 78 D-167 보충2 동기 전환). {@link OrderTerminated}를
 * 소비해 orderId로 OrderItem을 재조회한 뒤 각 품목의 예약을 {@link InventoryService#release}로 해제한다.
 *
 * <p><b>재고 해제 단일 수렴(원칙 3)</b>: 재고 예약 해제는 본 핸들러(OrderTerminated 단일 구독)로 수렴한다. 결제 실패·만료·결제창
 * 이탈 등 모든 미결제 종료는 Order 종료 경로가 OrderTerminated를 발행하며, Payment 이벤트(PaymentFailed)를 직접 구독하지 않는다.
 *
 * <p><b>실행 시점(Track 78 D-167 보충2)</b>: {@code @EventListener} 동기 소비 — 발행 트랜잭션({@code OrderAutoCancelService.cancelOne}
 * 조건부 UPDATE)과 같은 TX에서 실행된다. 해제 실패(INV-3·Inventory 미존재)는 예외를 그대로 전파해 주문 종료 전이까지 롤백한다 —
 * 주문은 PENDING_PAYMENT로 남아 다음 스케줄 주기(OrderAutoCancel·ExpirePayment)에 재시도된다(구 AFTER_COMMIT + REQUIRES_NEW +
 * 실패 흡수 + ExpiredOrderCleanup 재발행 폐기). 조건부 UPDATE가 OrderTerminated 1회를 보장하므로 variant 합계 reserved==0
 * 1차 가드(타 주문 예약과 구분 불가)는 두지 않는다.
 *
 * <p><b>락 순서</b>: 다중 품목은 variant id 오름차순으로 {@code SELECT ... FOR UPDATE}를 획득해 동시 종료·주문 간 데드락을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryOrderTerminatedHandler {

    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;

    @EventListener
    public void handle(OrderTerminated event) {
        List<OrderItem> items = orderItemRepository.findByOrderId(event.orderId()).stream()
                .sorted(Comparator.comparing(OrderItem::getVariantId))
                .toList();
        for (OrderItem item : items) {
            inventoryService.release(item.getVariantId(), item.getQuantity());
            log.info("[Inventory] event=OrderTerminated target_id={} action=release variant_id={} qty={}",
                    item.getId(), item.getVariantId(), item.getQuantity());
        }
    }
}

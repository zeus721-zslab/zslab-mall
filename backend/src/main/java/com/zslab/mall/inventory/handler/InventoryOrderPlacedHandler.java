package com.zslab.mall.inventory.handler;

import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderItemRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OrderPlaced(E1) → Inventory 예약 핸들러(Track 17 D-101 §3·Track 78 D-167 보충 동기 전환·R4). {@link OrderPlaced}를 소비해
 * orderId로 OrderItem을 재조회한 뒤(D-30 사실 통지·payload 무복제) 각 품목에 대해 {@link InventoryService#reserve}로 재고를 예약한다.
 *
 * <p><b>실행 시점(Track 78 D-167 보충·R4)</b>: {@code @EventListener} 동기 소비 — 발행 트랜잭션({@code OrderService.createOrder})
 * 안에서 실행된다. 예약 실패(INV-1 oversell·Inventory 미존재)는 예외를 그대로 전파해 주문 생성 자체를 롤백한다 — "예약 없는
 * PENDING_PAYMENT 주문" 잔존을 원천 차단한다(구 AFTER_COMMIT + REQUIRES_NEW + 실패 흡수 폐기). 주문 생성 직후라 품목은 전부
 * ORDERED이며 단일 전달이므로 item_status 1차 가드는 두지 않는다({@code InventoryPaymentCompletedHandler} 정합).
 *
 * <p><b>락 순서</b>: 다중 품목은 variant id 오름차순으로 {@code SELECT ... FOR UPDATE}를 획득해 동시 주문 간 데드락을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryOrderPlacedHandler {

    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;

    @EventListener
    public void handle(OrderPlaced event) {
        List<OrderItem> items = orderItemRepository.findByOrderId(event.orderId()).stream()
                .sorted(Comparator.comparing(OrderItem::getVariantId))
                .toList();
        for (OrderItem item : items) {
            inventoryService.reserve(item.getVariantId(), item.getQuantity());
            log.info("[Inventory] event=OrderPlaced target_id={} action=reserve variant_id={} qty={}",
                    item.getId(), item.getVariantId(), item.getQuantity());
        }
    }
}

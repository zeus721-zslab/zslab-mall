package com.zslab.mall.inventory.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.event.OrderCancelled;
import com.zslab.mall.order.repository.OrderItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link InventoryOrderCancelledHandler} 단위 검증(Mockito·D-153 Phase 1). 정상 해제·잔여 reserved == 0 skip
 * (read-only findByVariantId)·해제 실패(INV-3) 시 catch → recordFailed 흡수를 커버한다
 * ({@link InventoryPaymentFailedHandlerTest} 원형 복제·구독 이벤트만 OrderCancelled).
 */
@ExtendWith(MockitoExtension.class)
class InventoryOrderCancelledHandlerTest {

    private static final Long ORDER_ID = 700L;
    private static final Long PRODUCT_ID = 11L;
    private static final Long VARIANT_ID = 1L;
    private static final Long SELLER_ID = 21L;
    private static final long UNIT_PRICE = 1_000L;
    private static final int QTY = 2;

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private EventMetricsRecorder eventMetricsRecorder;
    @InjectMocks
    private InventoryOrderCancelledHandler handler;

    private OrderCancelled event() {
        return new OrderCancelled("ord_AUTOCANCEL00000000000000", ORDER_ID, LocalDateTime.of(2026, 7, 9, 10, 0));
    }

    private OrderItem orderItem() {
        OrderItem item = OrderItem.create(PRODUCT_ID, VARIANT_ID, SELLER_ID, QTY, UNIT_PRICE, UNIT_PRICE * QTY);
        ReflectionTestUtils.setField(item, "id", 501L);
        ReflectionTestUtils.setField(item, "itemStatus", OrderItemStatus.CANCELLED);
        return item;
    }

    private Inventory inventory(int reserved) {
        Inventory inventory = BeanUtils.instantiateClass(Inventory.class);
        ReflectionTestUtils.setField(inventory, "id", 10L);
        ReflectionTestUtils.setField(inventory, "variantId", VARIANT_ID);
        ReflectionTestUtils.setField(inventory, "quantityOnHand", 10);
        ReflectionTestUtils.setField(inventory, "quantityReserved", reserved);
        ReflectionTestUtils.setField(inventory, "quantityAvailable", 10 - reserved);
        return inventory;
    }

    @Test
    @DisplayName("정상: 잔여 reserved > 0 → release(variantId, qty) 호출")
    void handle_reservedPositive_releases() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem()));
        when(inventoryRepository.findByVariantId(VARIANT_ID)).thenReturn(Optional.of(inventory(5)));

        handler.handle(event());

        verify(inventoryService).release(VARIANT_ID, QTY);
        verify(eventMetricsRecorder, never()).recordFailed(any());
    }

    @Test
    @DisplayName("skip: 잔여 reserved == 0 → release 미호출(멱등)")
    void handle_reservedZero_skips() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem()));
        when(inventoryRepository.findByVariantId(VARIANT_ID)).thenReturn(Optional.of(inventory(0)));

        handler.handle(event());

        verify(inventoryService, never()).release(anyLong(), anyInt());
        verify(eventMetricsRecorder, never()).recordFailed(any());
    }

    @Test
    @DisplayName("해제 초과(INV-3): release throw → catch·recordFailed(OrderCancelled)·예외 흡수")
    void handle_releaseThrows_recordsFailed() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem()));
        when(inventoryRepository.findByVariantId(VARIANT_ID)).thenReturn(Optional.of(inventory(1)));
        doThrow(new InventoryInvariantViolationException("불법 재고 해제"))
                .when(inventoryService).release(VARIANT_ID, QTY);

        handler.handle(event());

        verify(eventMetricsRecorder).recordFailed(eq("OrderCancelled"));
    }
}

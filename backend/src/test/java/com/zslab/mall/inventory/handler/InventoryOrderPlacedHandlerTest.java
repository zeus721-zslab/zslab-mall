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
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link InventoryOrderPlacedHandler} 단위 검증(Mockito·D-101 §3·§6). 정상 예약·item_status != ORDERED skip·
 * 예약 실패(INV-1) 시 catch → recordFailed 흡수를 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryOrderPlacedHandlerTest {

    private static final Long ORDER_ID = 100L;
    private static final Long PRODUCT_ID = 11L;
    private static final Long VARIANT_ID = 1L;
    private static final Long SELLER_ID = 21L;
    private static final long UNIT_PRICE = 1_000L;
    private static final int QTY = 2;

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private EventMetricsRecorder eventMetricsRecorder;
    @InjectMocks
    private InventoryOrderPlacedHandler handler;

    private OrderPlaced event() {
        return new OrderPlaced("ord_ABC", ORDER_ID, LocalDateTime.of(2026, 7, 1, 10, 0));
    }

    private OrderItem orderItem(OrderItemStatus status) {
        OrderItem item = OrderItem.create(PRODUCT_ID, VARIANT_ID, SELLER_ID, QTY, UNIT_PRICE, UNIT_PRICE * QTY);
        ReflectionTestUtils.setField(item, "id", 501L);
        ReflectionTestUtils.setField(item, "itemStatus", status);
        return item;
    }

    @Test
    @DisplayName("정상: item ORDERED → reserve(variantId, qty) 호출·recordFailed 미호출")
    void handle_ordered_reserves() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(OrderItemStatus.ORDERED)));

        handler.handle(event());

        verify(inventoryService).reserve(VARIANT_ID, QTY);
        verify(eventMetricsRecorder, never()).recordFailed(any());
    }

    @Test
    @DisplayName("skip: item_status != ORDERED(PAID) → reserve 미호출")
    void handle_notOrdered_skips() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(OrderItemStatus.PAID)));

        handler.handle(event());

        verify(inventoryService, never()).reserve(anyLong(), anyInt());
        verify(eventMetricsRecorder, never()).recordFailed(any());
    }

    @Test
    @DisplayName("재고 부족(INV-1): reserve throw → catch·recordFailed(OrderPlaced)·예외 흡수")
    void handle_reserveThrows_recordsFailed() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(OrderItemStatus.ORDERED)));
        doThrow(new InventoryInvariantViolationException("불법 재고 예약"))
                .when(inventoryService).reserve(VARIANT_ID, QTY);

        handler.handle(event()); // 예외가 밖으로 전파되지 않아야 한다

        verify(eventMetricsRecorder).recordFailed(eq("OrderPlaced"));
    }
}

package com.zslab.mall.inventory.handler;

import static org.mockito.ArgumentMatchers.any;
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
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.payment.event.PaymentCompleted;
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
 * {@link InventoryPaymentCompletedHandler} 단위 검증(Mockito·D-101 §3·§6 갱신 A′). 1차 가드가 없으므로 item_status가 PAID여도
 * commitReservation을 진행함(A′ 증명)·차감 실패 시 catch → recordFailed 흡수를 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryPaymentCompletedHandlerTest {

    private static final Long ORDER_ID = 100L;
    private static final Long PAYMENT_ID = 7L;
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
    private InventoryPaymentCompletedHandler handler;

    private PaymentCompleted event() {
        return new PaymentCompleted(PAYMENT_ID, ORDER_ID, 2_000L, "pg_tid", LocalDateTime.of(2026, 7, 1, 10, 0));
    }

    private OrderItem orderItem(OrderItemStatus status) {
        OrderItem item = OrderItem.create(PRODUCT_ID, VARIANT_ID, SELLER_ID, QTY, UNIT_PRICE, UNIT_PRICE * QTY);
        ReflectionTestUtils.setField(item, "id", 501L);
        ReflectionTestUtils.setField(item, "itemStatus", status);
        return item;
    }

    @Test
    @DisplayName("정상(A′): item이 PAID여도 1차 가드 없이 commitReservation(variantId, qty, order, orderId) 진행")
    void handle_paidItem_stillCommits() {
        // markPaid 동기 소비로 AFTER_COMMIT 시점 item은 이미 PAID → 그래도 진행해야 함(A′)
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(OrderItemStatus.PAID)));

        handler.handle(event());

        verify(inventoryService).commitReservation(VARIANT_ID, QTY, "order", ORDER_ID);
        verify(eventMetricsRecorder, never()).recordFailed(any());
    }

    @Test
    @DisplayName("예약 부족(INV-3): commitReservation throw → catch·recordFailed(PaymentCompleted)·예외 흡수")
    void handle_commitThrows_recordsFailed() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(OrderItemStatus.PAID)));
        doThrow(new InventoryInvariantViolationException("불법 재고 차감·예약 부족"))
                .when(inventoryService).commitReservation(VARIANT_ID, QTY, "order", ORDER_ID);

        handler.handle(event());

        verify(eventMetricsRecorder).recordFailed(eq("PaymentCompleted"));
    }
}

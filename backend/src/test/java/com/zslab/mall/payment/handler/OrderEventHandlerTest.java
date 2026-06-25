package com.zslab.mall.payment.handler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.event.PaymentCompleted;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OrderEventHandler} 검증(D-29·D-33). PaymentCompleted 소비 시 fetch join 선로딩 후 markPaid 위임·미존재 시 실패.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventHandlerTest {

    private static final Long ORDER_ID = 100L;
    private static final Long PAYMENT_ID = 7L;
    private static final Long AMOUNT = 10_000L;
    private static final String PG_TID = "tid_handler";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 26, 13, 0);

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderService orderService;
    @InjectMocks
    private OrderEventHandler orderEventHandler;

    private PaymentCompleted event() {
        return new PaymentCompleted(PAYMENT_ID, ORDER_ID, AMOUNT, PG_TID, OCCURRED_AT);
    }

    @Test
    @DisplayName("onPaymentCompleted: findByIdWithItems 선로딩(D-33) 후 markPaid(orderId, occurredAt) 위임")
    void onPaymentCompleted_fetchJoinThenMarkPaid() {
        Order order = Order.create(ORDER_ID, "20260626-AAAAAA", 0L, 0L);
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.of(order));

        orderEventHandler.onPaymentCompleted(event());

        verify(orderRepository).findByIdWithItems(ORDER_ID);
        verify(orderService).markPaid(ORDER_ID, OCCURRED_AT);
    }

    @Test
    @DisplayName("onPaymentCompleted: 주문 미발견 → IllegalArgumentException·markPaid 미호출")
    void onPaymentCompleted_orderNotFound_throws() {
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderEventHandler.onPaymentCompleted(event()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(orderService, never()).markPaid(eq(ORDER_ID), any());
    }
}

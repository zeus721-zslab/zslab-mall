package com.zslab.mall.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.event.OrderCancelled;
import com.zslab.mall.order.repository.OrderRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link OrderAutoCancelService} 단위 검증(Mockito·D-153 Phase 1). 정상 자동취소(전 품목 CANCELLED·status CANCELLED·
 * OrderCancelled 발행)와 멱등 skip(PENDING_PAYMENT 아님 → no-op·이벤트 미발행)을 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderAutoCancelServiceTest {

    private static final Long ORDER_ID = 700L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderStatusResolver orderStatusResolver;
    @Mock
    private TracedEventPublisher eventPublisher;
    @InjectMocks
    private OrderAutoCancelService orderAutoCancelService;

    private Order pendingOrderWithItems() {
        Order order = Order.create(100L, "20260709-ABCDEF", 0L, 0L);
        order.addItem(OrderItem.create(10L, 20L, 30L, 2, 5_000L, 10_000L));
        order.addItem(OrderItem.create(11L, 21L, 30L, 1, 3_000L, 3_000L));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "publicId", "ord_AUTOCANCEL00000000000000");
        return order;
    }

    @Test
    @DisplayName("정상: 전 OrderItem CANCELLED·status CANCELLED·OrderCancelled 발행")
    void cancelOne_pending_cancelsAndPublishes() {
        Order order = pendingOrderWithItems();
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderStatusResolver.resolve(any())).thenReturn(OrderStatus.CANCELLED);

        orderAutoCancelService.cancelOne(ORDER_ID);

        assertThat(order.getItems())
                .allSatisfy(item -> assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.CANCELLED));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(eventPublisher).publishEvent(any(OrderCancelled.class));
    }

    @Test
    @DisplayName("멱등 skip: PENDING_PAYMENT 아님 → no-op·이벤트 미발행·Resolver 미호출")
    void cancelOne_notPending_skips() {
        Order order = pendingOrderWithItems();
        order.applyResolvedStatus(OrderStatus.PAID);   // 조회~트랜잭션 사이 결제 완료된 상황
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.of(order));

        orderAutoCancelService.cancelOne(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getItems())
                .allSatisfy(item -> assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.ORDERED));
        verify(eventPublisher, never()).publishEvent(any());
        verify(orderStatusResolver, never()).resolve(any());
    }

    @Test
    @DisplayName("멱등 skip: 주문 행 없음 → no-op·이벤트 미발행")
    void cancelOne_notFound_skips() {
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.empty());

        orderAutoCancelService.cancelOne(ORDER_ID);

        verify(eventPublisher, never()).publishEvent(any());
        verify(orderStatusResolver, never()).resolve(any());
    }
}

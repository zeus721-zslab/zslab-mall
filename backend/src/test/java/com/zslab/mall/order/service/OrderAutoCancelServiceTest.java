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
import com.zslab.mall.order.event.OrderTerminated;
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
 * {@link OrderAutoCancelService} 단위 검증(Mockito·FE-12c). 정상 미결제 종료(Order.status=PAYMENT_EXPIRED 직접 세팅·
 * OrderItem 무변경·OrderTerminated 발행)와 멱등 skip(PENDING_PAYMENT 아님·주문 없음 → no-op·이벤트 미발행)을 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderAutoCancelServiceTest {

    private static final Long ORDER_ID = 700L;

    @Mock
    private OrderRepository orderRepository;
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
    @DisplayName("정상: status PAYMENT_EXPIRED 직접 세팅·OrderItem 무변경·OrderTerminated 발행")
    void cancelOne_pending_expiresAndPublishes() {
        Order order = pendingOrderWithItems();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        orderAutoCancelService.cancelOne(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);
        // OrderItem은 종료 대상 아님 — ORDERED 유지(재고 해제는 OrderTerminated 핸들러가 variant_id로 수행)
        assertThat(order.getItems())
                .allSatisfy(item -> assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.ORDERED));
        verify(eventPublisher).publishEvent(any(OrderTerminated.class));
    }

    @Test
    @DisplayName("멱등 skip: PENDING_PAYMENT 아님 → no-op·이벤트 미발행")
    void cancelOne_notPending_skips() {
        Order order = pendingOrderWithItems();
        order.applyResolvedStatus(OrderStatus.PAID);   // 조회~트랜잭션 사이 결제 완료된 상황
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        orderAutoCancelService.cancelOne(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("멱등 skip: 주문 행 없음 → no-op·이벤트 미발행")
    void cancelOne_notFound_skips() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        orderAutoCancelService.cancelOne(ORDER_ID);

        verify(eventPublisher, never()).publishEvent(any());
    }
}

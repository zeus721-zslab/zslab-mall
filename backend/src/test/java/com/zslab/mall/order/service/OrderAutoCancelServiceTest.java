package com.zslab.mall.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link OrderAutoCancelService} 단위 검증(Mockito·FE-12c·Track 78 D-167 조건부 UPDATE). 정상 미결제 종료(조건부 UPDATE 영향 행 1 →
 * OrderTerminated 발행)와 멱등 skip(영향 행 0 → 무처리·이벤트 미발행·재조회 없음)을 커버한다.
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

    private Order orderWithItems() {
        Order order = Order.create(100L, "20260709-ABCDEF", 0L, 0L);
        order.addItem(OrderItem.create(10L, 20L, 30L, "테스트 상품", 2, 5_000L, 10_000L, 1000));
        order.addItem(OrderItem.create(11L, 21L, 30L, "테스트 상품", 1, 3_000L, 3_000L, 1000));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "publicId", "ord_AUTOCANCEL00000000000000");
        return order;
    }

    @Test
    @DisplayName("정상: 조건부 UPDATE(PENDING_PAYMENT→PAYMENT_EXPIRED) 영향 행 1 → 재조회 후 OrderTerminated 발행·OrderItem 무변경")
    void cancelOne_pending_expiresAndPublishes() {
        Order order = orderWithItems();
        when(orderRepository.transitionStatus(
                eq(ORDER_ID), eq(OrderStatus.PENDING_PAYMENT), eq(OrderStatus.PAYMENT_EXPIRED), any(LocalDateTime.class)))
                .thenReturn(1);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        orderAutoCancelService.cancelOne(ORDER_ID);

        // OrderItem은 종료 대상 아님 — ORDERED 유지(재고 해제는 OrderTerminated 핸들러가 variant_id로 수행)
        assertThat(order.getItems())
                .allSatisfy(item -> assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.ORDERED));
        ArgumentCaptor<OrderTerminated> captor = ArgumentCaptor.forClass(OrderTerminated.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().publicId()).isEqualTo("ord_AUTOCANCEL00000000000000");
    }

    @Test
    @DisplayName("멱등 skip: 조건부 UPDATE 영향 행 0(PENDING_PAYMENT 아님·행 없음) → 재조회 없음·이벤트 미발행")
    void cancelOne_notPending_skips() {
        when(orderRepository.transitionStatus(
                eq(ORDER_ID), eq(OrderStatus.PENDING_PAYMENT), eq(OrderStatus.PAYMENT_EXPIRED), any(LocalDateTime.class)))
                .thenReturn(0);

        orderAutoCancelService.cancelOne(ORDER_ID);

        verify(orderRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}

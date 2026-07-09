package com.zslab.mall.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.command.CreateOrderCommand;
import com.zslab.mall.order.command.OrderItemCommand;
import com.zslab.mall.order.command.ShippingAddressCommand;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OrderService} 오케스트레이션 검증(Mockito). createOrder 가드·정상 흐름·markPaid·recalculateStatus.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusResolver orderStatusResolver;

    @Mock
    private TracedEventPublisher eventPublisher;

    @InjectMocks
    private OrderService orderService;

    private ShippingAddressCommand shipping() {
        return new ShippingAddressCommand(
                "홍길동", "010-1234-5678", "06236", "서울 강남대로 1", null, "101호", null);
    }

    private CreateOrderCommand commandWithItems(int itemCount) {
        OrderItemCommand item = new OrderItemCommand(10L, 20L, 30L, 2, 5_000L, 10_000L);
        return new CreateOrderCommand(100L, java.util.Collections.nCopies(itemCount, item), shipping(), 0L, 1_000L);
    }

    @Test
    @DisplayName("createOrder: items 0개 → IllegalArgumentException (ORD-1)")
    void createOrder_noItems_throws() {
        CreateOrderCommand command = new CreateOrderCommand(100L, List.of(), shipping(), 0L, 0L);
        assertThatThrownBy(() -> orderService.createOrder(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORD-1");
    }

    @Test
    @DisplayName("createOrder: 정상 → Order 구성·save·OrderPlaced 발행")
    void createOrder_happyPath() {
        when(orderRepository.existsByOrderNo(anyString())).thenReturn(false);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.createOrder(commandWithItems(2));

        assertThat(result.getBuyerId()).isEqualTo(100L);
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getTotalPrice()).isEqualTo(20_000L);
        assertThat(result.getShippingSnapshot()).isNotNull();
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.getOrderNo()).matches("\\d{8}-[0-9A-Z]{6}");
        verify(orderRepository).save(any(Order.class));
        verify(eventPublisher).publishEvent(any(OrderPlaced.class));
    }

    @Test
    @DisplayName("createOrder: order_no 1차 충돌 시 재시도 후 성공")
    void createOrder_retriesOnCollision() {
        when(orderRepository.existsByOrderNo(anyString())).thenReturn(true, false);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.createOrder(commandWithItems(1));

        assertThat(result).isNotNull();
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("markPaid: 조회 후 규칙 [1] 적용 (status PAID)")
    void markPaid_delegates() {
        Order order = Order.create(100L, "20260625-ABCDEF", 0L, 0L);
        order.addItem(OrderItem.create(10L, 20L, 30L, 1, 5_000L, 5_000L));
        when(orderRepository.findById(1L)).thenReturn(java.util.Optional.of(order));

        Order result = orderService.markPaid(1L, LocalDateTime.of(2026, 6, 25, 9, 0));

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(result.getItems())
                .allSatisfy(item -> assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.PAID));
    }

    @Test
    @DisplayName("recalculateStatus: Resolver 결과를 Order에 반영")
    void recalculateStatus_appliesResolverResult() {
        Order order = Order.create(100L, "20260625-ABCDEF", 0L, 0L);
        order.addItem(OrderItem.create(10L, 20L, 30L, 1, 5_000L, 5_000L));
        when(orderRepository.findById(1L)).thenReturn(java.util.Optional.of(order));
        when(orderStatusResolver.resolve(any())).thenReturn(OrderStatus.SHIPPING);

        Order result = orderService.recalculateStatus(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.SHIPPING);
        verify(orderStatusResolver).resolve(any());
    }

    @Test
    @DisplayName("markPaid: 미존재 주문 → IllegalArgumentException")
    void markPaid_notFound_throws() {
        when(orderRepository.findById(999L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> orderService.markPaid(999L, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markPaid: 미결제 종료(PAYMENT_EXPIRED) 주문 → IllegalStateException(늦은 웹훅 차단·FE-12c)·PAID 미전이")
    void markPaid_terminatedOrder_rejects() {
        Order order = Order.create(100L, "20260709-EXPIRE", 0L, 0L);
        order.addItem(OrderItem.create(10L, 20L, 30L, 1, 5_000L, 5_000L));
        order.expirePayment();   // PENDING_PAYMENT → PAYMENT_EXPIRED (이미 미결제 종료된 주문)
        when(orderRepository.findById(1L)).thenReturn(java.util.Optional.of(order));

        assertThatThrownBy(() -> orderService.markPaid(1L, LocalDateTime.of(2026, 7, 9, 9, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("늦은 웹훅");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);   // PAID 미전이
        assertThat(order.getItems())
                .allSatisfy(item -> assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.ORDERED));
    }
}

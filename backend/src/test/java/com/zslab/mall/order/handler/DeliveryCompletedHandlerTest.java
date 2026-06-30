package com.zslab.mall.order.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DeliveryCompletedHandler} 단위 검증(Track 13·D-97 Q4·Q5·Q8). E5 동기 소비로 OrderItem을 DELIVERED로 전이하고
 * Order.status 재계산을 위임하는 정상 흐름과 미발견·멱등·canTransitionTo 위반 가드를 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryCompletedHandlerTest {

    private static final Long DELIVERY_ID = 1L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long ORDER_ID = 50L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 30, 9, 0);

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderService orderService;
    @InjectMocks
    private DeliveryCompletedHandler handler;

    private DeliveryCompleted event() {
        return new DeliveryCompleted(DELIVERY_ID, ORDER_ITEM_ID, OCCURRED_AT, OCCURRED_AT);
    }

    @Test
    @DisplayName("onDeliveryCompleted: SHIPPING OrderItem → DELIVERED 전이·recalculateStatus 위임")
    void onDeliveryCompleted_shipping_transitionsToDelivered() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.SHIPPING);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));

        handler.onDeliveryCompleted(event());

        verify(orderItem).changeStatus(OrderItemStatus.DELIVERED);
        verify(orderService).recalculateStatus(ORDER_ID);
    }

    @Test
    @DisplayName("onDeliveryCompleted: OrderItem 미발견 → no-op(전이·재계산 없음)")
    void onDeliveryCompleted_itemNotFound_noOp() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        handler.onDeliveryCompleted(event());

        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onDeliveryCompleted: 이미 DELIVERED → 멱등 no-op(전이·재계산 없음·D-97 Q8)")
    void onDeliveryCompleted_alreadyDelivered_idempotentNoOp() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.DELIVERED);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        handler.onDeliveryCompleted(event());

        verify(orderItem, never()).changeStatus(any());
        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onDeliveryCompleted: PREPARING(전이 불가) → canTransitionTo 가드 skip(전이·재계산 없음·D-97 Q8)")
    void onDeliveryCompleted_preparing_canTransitionGuardSkip() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.PREPARING);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        handler.onDeliveryCompleted(event());

        verify(orderItem, never()).changeStatus(any());
        verify(orderService, never()).recalculateStatus(anyLong());
    }
}

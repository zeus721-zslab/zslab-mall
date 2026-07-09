package com.zslab.mall.cart.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.cart.repository.CartItemRepository;
import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderRepository;
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
 * {@link CartPaymentCompletedHandler} 단위 검증(Mockito·Track 67). findByIdWithItems → buyerId·variantIds 조달·
 * {@code deleteByUserIdAndVariantIdIn} 호출·distinct(Set)·order 미발견 skip·실패 흡수(recordFailed)를 커버한다
 * (InventoryPaymentCompletedHandlerTest 대칭·handle() 직접 호출·AFTER_COMMIT 실발화는 통합 테스트 소관).
 */
@ExtendWith(MockitoExtension.class)
class CartPaymentCompletedHandlerTest {

    private static final Long PAYMENT_ID = 500L;
    private static final Long ORDER_ID = 300L;
    private static final Long BUYER_ID = 77L;
    private static final Long AMOUNT = 10_000L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private EventMetricsRecorder eventMetricsRecorder;
    @InjectMocks
    private CartPaymentCompletedHandler handler;

    private PaymentCompleted event() {
        return new PaymentCompleted(PAYMENT_ID, ORDER_ID, AMOUNT, "pg_tid_T67", LocalDateTime.of(2026, 7, 9, 10, 0));
    }

    private Order orderWithVariants(Long... variantIds) {
        Order order = Order.create(BUYER_ID, "20260709-TEST01", 0L, 0L);
        for (Long variantId : variantIds) {
            order.addItem(OrderItem.create(11L, variantId, 21L, 1, 1_000L, 1_000L));
        }
        return order;
    }

    @Test
    @DisplayName("정상: findByIdWithItems → deleteByUserIdAndVariantIdIn(buyerId, 주문 variantId 집합)·recordFailed 미호출")
    void handle_deletesOrderedVariants() {
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.of(orderWithVariants(201L, 202L)));

        handler.handle(event());

        verify(cartItemRepository).deleteByUserIdAndVariantIdIn(eq(BUYER_ID),
                argThat(ids -> ids.size() == 2 && ids.contains(201L) && ids.contains(202L)));
        verify(eventMetricsRecorder, never()).recordFailed(any());
    }

    @Test
    @DisplayName("distinct: 동일 variant 복수 OrderItem → variantId Set 중복 제거(size 2)")
    void handle_distinctVariantIds() {
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.of(orderWithVariants(201L, 201L, 202L)));

        handler.handle(event());

        verify(cartItemRepository).deleteByUserIdAndVariantIdIn(eq(BUYER_ID),
                argThat(ids -> ids.size() == 2 && ids.contains(201L) && ids.contains(202L)));
    }

    @Test
    @DisplayName("order 미발견: skip → delete 미호출·recordFailed 미호출")
    void handle_orderNotFound_skips() {
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.empty());

        handler.handle(event());

        verify(cartItemRepository, never()).deleteByUserIdAndVariantIdIn(anyLong(), any());
        verify(eventMetricsRecorder, never()).recordFailed(any());
    }

    @Test
    @DisplayName("삭제 실패: delete throw → catch·recordFailed(PaymentCompleted)·예외 흡수(전파 안 됨)")
    void handle_deleteThrows_recordsFailed() {
        when(orderRepository.findByIdWithItems(ORDER_ID)).thenReturn(Optional.of(orderWithVariants(201L)));
        doThrow(new RuntimeException("DB down"))
                .when(cartItemRepository).deleteByUserIdAndVariantIdIn(anyLong(), any());

        handler.handle(event()); // 예외가 밖으로 전파되지 않아야 한다

        verify(eventMetricsRecorder).recordFailed(eq("PaymentCompleted"));
    }
}

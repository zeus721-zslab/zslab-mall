package com.zslab.mall.inventory.handler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link InventoryPaymentCompletedHandler} 단위 검증(Mockito·D-101 §3·Track 78 D-167 동기 전환). 1차 가드가 없으므로 item_status가
 * PAID여도 commitReservation을 진행함(A′ 증명)·차감 실패 시 예외 전파(결제 롤백 유도)·variant id 오름차순 처리를 커버한다.
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
    @InjectMocks
    private InventoryPaymentCompletedHandler handler;

    private PaymentCompleted event() {
        return new PaymentCompleted(PAYMENT_ID, ORDER_ID, 2_000L, "pg_tid", LocalDateTime.of(2026, 7, 1, 10, 0));
    }

    private OrderItem orderItem(Long id, Long variantId, OrderItemStatus status) {
        OrderItem item = OrderItem.create(PRODUCT_ID, variantId, SELLER_ID, "테스트 상품", QTY, UNIT_PRICE, UNIT_PRICE * QTY);
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "itemStatus", status);
        return item;
    }

    @Test
    @DisplayName("정상(A′): item이 PAID여도 1차 가드 없이 commitReservation(variantId, qty, order, orderId) 진행")
    void handle_paidItem_stillCommits() {
        // 동기 소비 순서상 markPaid가 먼저 실행되면 item은 이미 PAID → 그래도 진행해야 함(A′)
        when(orderItemRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(orderItem(501L, VARIANT_ID, OrderItemStatus.PAID)));

        handler.handle(event());

        verify(inventoryService).commitReservation(VARIANT_ID, QTY, "order", ORDER_ID);
    }

    @Test
    @DisplayName("예약 부족(INV-3): commitReservation throw → 흡수하지 않고 예외 전파(결제 완료 롤백·Track 78 R1)")
    void handle_commitThrows_propagates() {
        when(orderItemRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(orderItem(501L, VARIANT_ID, OrderItemStatus.PAID)));
        doThrow(new InventoryInvariantViolationException("불법 재고 차감·예약 부족"))
                .when(inventoryService).commitReservation(VARIANT_ID, QTY, "order", ORDER_ID);

        assertThatThrownBy(() -> handler.handle(event()))
                .isInstanceOf(InventoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("다중 품목: 조회 순서와 무관하게 variant id 오름차순으로 commitReservation(FOR UPDATE 락 순서 고정)")
    void handle_multipleItems_commitsInVariantIdOrder() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                orderItem(502L, 30L, OrderItemStatus.PAID),
                orderItem(503L, 10L, OrderItemStatus.PAID),
                orderItem(504L, 20L, OrderItemStatus.PAID)));

        handler.handle(event());

        InOrder inOrder = inOrder(inventoryService);
        inOrder.verify(inventoryService).commitReservation(10L, QTY, "order", ORDER_ID);
        inOrder.verify(inventoryService).commitReservation(20L, QTY, "order", ORDER_ID);
        inOrder.verify(inventoryService).commitReservation(30L, QTY, "order", ORDER_ID);
    }
}

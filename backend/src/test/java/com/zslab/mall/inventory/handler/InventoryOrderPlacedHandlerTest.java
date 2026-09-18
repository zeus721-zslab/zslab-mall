package com.zslab.mall.inventory.handler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.order.repository.OrderItemRepository;
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
 * {@link InventoryOrderPlacedHandler} 단위 검증(Mockito·D-101 §3·Track 78 D-167 보충 동기 전환·R4). 정상 예약·예약 실패(INV-1)
 * 시 예외 전파(주문 생성 롤백 유도)·variant id 오름차순 처리를 커버한다.
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
    @InjectMocks
    private InventoryOrderPlacedHandler handler;

    private OrderPlaced event() {
        return new OrderPlaced("ord_TEST", ORDER_ID, LocalDateTime.of(2026, 7, 1, 10, 0));
    }

    private OrderItem orderItem(Long id, Long variantId) {
        OrderItem item = OrderItem.create(PRODUCT_ID, variantId, SELLER_ID, "테스트 상품", QTY, UNIT_PRICE, UNIT_PRICE * QTY, 1000);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    @Test
    @DisplayName("정상: item ORDERED → reserve(variantId, qty) 호출")
    void handle_orderedItem_reserves() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(501L, VARIANT_ID)));

        handler.handle(event());

        verify(inventoryService).reserve(VARIANT_ID, QTY);
    }

    @Test
    @DisplayName("재고 부족(INV-1): reserve throw → 흡수하지 않고 예외 전파(주문 생성 롤백·Track 78 R4)")
    void handle_reserveThrows_propagates() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(501L, VARIANT_ID)));
        doThrow(new InventoryInvariantViolationException("불법 재고 예약"))
                .when(inventoryService).reserve(VARIANT_ID, QTY);

        assertThatThrownBy(() -> handler.handle(event()))
                .isInstanceOf(InventoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("다중 품목: 조회 순서와 무관하게 variant id 오름차순으로 reserve(FOR UPDATE 락 순서 고정)")
    void handle_multipleItems_reservesInVariantIdOrder() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                orderItem(502L, 30L),
                orderItem(503L, 10L),
                orderItem(504L, 20L)));

        handler.handle(event());

        InOrder inOrder = inOrder(inventoryService);
        inOrder.verify(inventoryService).reserve(10L, QTY);
        inOrder.verify(inventoryService).reserve(20L, QTY);
        inOrder.verify(inventoryService).reserve(30L, QTY);
    }
}

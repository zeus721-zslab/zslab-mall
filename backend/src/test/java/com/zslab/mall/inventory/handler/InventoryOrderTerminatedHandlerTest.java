package com.zslab.mall.inventory.handler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.event.OrderTerminated;
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
 * {@link InventoryOrderTerminatedHandler} 단위 검증(Mockito·FE-12c·Track 78 D-167 보충2 동기 전환). 정상 해제·해제 실패(INV-3)
 * 시 예외 전파(종료 전이 롤백 유도)·variant id 오름차순 처리를 커버한다(구 InventoryOrderCancelledHandlerTest 개명).
 */
@ExtendWith(MockitoExtension.class)
class InventoryOrderTerminatedHandlerTest {

    private static final Long ORDER_ID = 700L;
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
    private InventoryOrderTerminatedHandler handler;

    private OrderTerminated event() {
        return new OrderTerminated("ord_AUTOCANCEL00000000000000", ORDER_ID, LocalDateTime.of(2026, 7, 9, 10, 0));
    }

    /** 미결제 종료는 OrderItem을 전이시키지 않으므로 종료 후에도 ORDERED 유지(재고 해제는 variant_id 기반). */
    private OrderItem orderItem(Long id, Long variantId) {
        OrderItem item = OrderItem.create(PRODUCT_ID, variantId, SELLER_ID, "테스트 상품", QTY, UNIT_PRICE, UNIT_PRICE * QTY);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    @Test
    @DisplayName("정상: item별 release(variantId, qty) 호출(1차 가드 없음)")
    void handle_releasesEachItem() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(501L, VARIANT_ID)));

        handler.handle(event());

        verify(inventoryService).release(VARIANT_ID, QTY);
    }

    @Test
    @DisplayName("해제 초과(INV-3): release throw → 흡수하지 않고 예외 전파(종료 전이 롤백·Track 78 보충2)")
    void handle_releaseThrows_propagates() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(orderItem(501L, VARIANT_ID)));
        doThrow(new InventoryInvariantViolationException("불법 재고 해제"))
                .when(inventoryService).release(VARIANT_ID, QTY);

        assertThatThrownBy(() -> handler.handle(event()))
                .isInstanceOf(InventoryInvariantViolationException.class);
    }

    @Test
    @DisplayName("다중 품목: 조회 순서와 무관하게 variant id 오름차순으로 release(FOR UPDATE 락 순서 고정)")
    void handle_multipleItems_releasesInVariantIdOrder() {
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                orderItem(502L, 30L),
                orderItem(503L, 10L),
                orderItem(504L, 20L)));

        handler.handle(event());

        InOrder inOrder = inOrder(inventoryService);
        inOrder.verify(inventoryService).release(10L, QTY);
        inOrder.verify(inventoryService).release(20L, QTY);
        inOrder.verify(inventoryService).release(30L, QTY);
    }
}

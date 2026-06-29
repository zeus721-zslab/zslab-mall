package com.zslab.mall.claim.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimCompleted;
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
 * {@link ClaimCompletedHandler} 단위 검증(D-90 Q4·중첩 AFTER_COMMIT). CANCEL 한정 OrderItem CANCEL_REQUESTED → CANCELLED
 * 종결·Order.status 재계산 위임과 type·null·멱등 가드를 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimCompletedHandlerTest {

    private static final Long CLAIM_ID = 1L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long ORDER_ID = 50L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 29, 9, 0);

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderService orderService;
    @InjectMocks
    private ClaimCompletedHandler handler;

    private ClaimCompleted event(ClaimType type) {
        return new ClaimCompleted(CLAIM_ID, "clm_x", ORDER_ITEM_ID, type, ClaimStatus.COMPLETED, OCCURRED_AT);
    }

    @Test
    @DisplayName("onClaimCompleted: CANCEL_REQUESTED OrderItem → CANCELLED 종결·recalculateStatus 위임")
    void onClaimCompleted_cancelRequested_terminatesToCancelled() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.CANCEL_REQUESTED);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));

        handler.onClaimCompleted(event(ClaimType.CANCEL));

        verify(orderItem).changeStatus(OrderItemStatus.CANCELLED);
        verify(orderService).recalculateStatus(ORDER_ID);
    }

    @Test
    @DisplayName("onClaimCompleted: type=RETURN → 미처리(findById·재계산 없음)")
    void onClaimCompleted_nonCancel_noOp() {
        handler.onClaimCompleted(event(ClaimType.RETURN));

        verify(orderItemRepository, never()).findById(anyLong());
        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onClaimCompleted: OrderItem 미발견 → no-op(재계산 없음)")
    void onClaimCompleted_itemNotFound_noOp() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        handler.onClaimCompleted(event(ClaimType.CANCEL));

        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onClaimCompleted: 이미 종결/비대상(CANCELLED) → 멱등 no-op(전이·재계산 없음)")
    void onClaimCompleted_alreadyCancelled_idempotentNoOp() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.CANCELLED);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        handler.onClaimCompleted(event(ClaimType.CANCEL));

        verify(orderItem, never()).changeStatus(any());
        verify(orderService, never()).recalculateStatus(anyLong());
    }
}

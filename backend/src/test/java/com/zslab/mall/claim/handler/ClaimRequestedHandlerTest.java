package com.zslab.mall.claim.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimRequested;
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
 * {@link ClaimRequestedHandler} 단위 검증(ClaimRefundCompletedHandler 패턴·D-90 Q1·D-88 Q6). CANCEL 한정 OrderItem
 * CANCEL_REQUESTED 전이·Order.status 재계산 위임과 type·null·멱등·전이불가 가드를 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimRequestedHandlerTest {

    private static final Long CLAIM_ID = 1L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long ORDER_ID = 50L;
    private static final Long BUYER_ID = 100L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 29, 9, 0);

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderService orderService;
    @InjectMocks
    private ClaimRequestedHandler handler;

    private ClaimRequested event(ClaimType type) {
        return new ClaimRequested(CLAIM_ID, "clm_x", ORDER_ITEM_ID, type, ClaimStatus.REQUESTED, BUYER_ID, OCCURRED_AT);
    }

    @Test
    @DisplayName("onClaimRequested: PAID OrderItem → CANCEL_REQUESTED 전이·recalculateStatus(orderId) 위임")
    void onClaimRequested_paid_transitionsAndRecalculates() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.PAID);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));

        handler.onClaimRequested(event(ClaimType.CANCEL));

        verify(orderItem).changeStatus(OrderItemStatus.CANCEL_REQUESTED);
        verify(orderService).recalculateStatus(ORDER_ID);
    }

    @Test
    @DisplayName("onClaimRequested: type=RETURN → 미처리(findById·재계산 없음)")
    void onClaimRequested_nonCancel_noOp() {
        handler.onClaimRequested(event(ClaimType.RETURN));

        verify(orderItemRepository, never()).findById(anyLong());
        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onClaimRequested: OrderItem 미발견 → no-op(재계산 없음)")
    void onClaimRequested_itemNotFound_noOp() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        handler.onClaimRequested(event(ClaimType.CANCEL));

        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onClaimRequested: 이미 CANCEL_REQUESTED → 멱등 no-op(전이·재계산 없음)")
    void onClaimRequested_alreadyCancelRequested_idempotentNoOp() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.CANCEL_REQUESTED);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        handler.onClaimRequested(event(ClaimType.CANCEL));

        verify(orderItem, never()).changeStatus(any());
        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onClaimRequested: 전이 불가 상태(SHIPPING) → 경고 후 no-op(전이·재계산 없음)")
    void onClaimRequested_notTransitionable_noOp() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.SHIPPING);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        handler.onClaimRequested(event(ClaimType.CANCEL));

        verify(orderItem, never()).changeStatus(any());
        verify(orderService, never()).recalculateStatus(anyLong());
    }
}

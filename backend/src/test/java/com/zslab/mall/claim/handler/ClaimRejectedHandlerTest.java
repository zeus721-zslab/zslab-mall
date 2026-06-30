package com.zslab.mall.claim.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimRejected;
import com.zslab.mall.claim.repository.ClaimRepository;
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
 * {@link ClaimRejectedHandler} 단위 검증(D-98 Q7·스냅샷 원복·D-90 Q3 의미 변경). type 무관 OrderItem *_REQUESTED →
 * 스냅샷 상태({@code claim.previousOrderItemStatus}) 복원·Order.status 재계산 위임과 claim/item null·비대상 가드를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimRejectedHandlerTest {

    private static final Long CLAIM_ID = 1L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final Long ORDER_ID = 50L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 29, 9, 0);

    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderService orderService;
    @InjectMocks
    private ClaimRejectedHandler handler;

    private ClaimRejected event(ClaimType type) {
        return new ClaimRejected(CLAIM_ID, "clm_x", ORDER_ITEM_ID, type, ClaimStatus.REJECTED, OCCURRED_AT);
    }

    /** 스냅샷 원복을 검증하는 테스트용 Claim mock(previousOrderItemStatus만 stub·핸들러는 getType 미사용). */
    private Claim claimWithSnapshot(OrderItemStatus snapshot) {
        Claim claim = mock(Claim.class);
        when(claim.getPreviousOrderItemStatus()).thenReturn(snapshot);
        return claim;
    }

    @Test
    @DisplayName("onClaimRejected: CANCEL·CANCEL_REQUESTED → 스냅샷(PAID) 원복·recalculateStatus 위임")
    void onClaimRejected_cancel_restoresToSnapshotPaid() {
        Claim claim = claimWithSnapshot(OrderItemStatus.PAID);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.CANCEL_REQUESTED);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));

        handler.onClaimRejected(event(ClaimType.CANCEL));

        verify(orderItem).changeStatus(OrderItemStatus.PAID);
        verify(orderService).recalculateStatus(ORDER_ID);
    }

    @Test
    @DisplayName("onClaimRejected: RETURN·RETURN_REQUESTED → 스냅샷(DELIVERED) 원복·recalculateStatus 위임")
    void onClaimRejected_return_restoresToSnapshotDelivered() {
        Claim claim = claimWithSnapshot(OrderItemStatus.DELIVERED);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getId()).thenReturn(ORDER_ITEM_ID);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));

        handler.onClaimRejected(event(ClaimType.RETURN));

        verify(orderItem).changeStatus(OrderItemStatus.DELIVERED);
        verify(orderService).recalculateStatus(ORDER_ID);
    }

    @Test
    @DisplayName("onClaimRejected: 클레임 미발견 → no-op(재계산 없음)")
    void onClaimRejected_claimNotFound_noOp() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

        handler.onClaimRejected(event(ClaimType.CANCEL));

        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onClaimRejected: OrderItem 미발견 → no-op(재계산 없음)")
    void onClaimRejected_itemNotFound_noOp() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(mock(Claim.class)));
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        handler.onClaimRejected(event(ClaimType.CANCEL));

        verify(orderService, never()).recalculateStatus(anyLong());
    }

    @Test
    @DisplayName("onClaimRejected: 비대상 상태(요청 상태 아님·PAID) → no-op(전이·재계산 없음)")
    void onClaimRejected_notRequestedStatus_noOp() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(mock(Claim.class)));
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getItemStatus()).thenReturn(OrderItemStatus.PAID);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        handler.onClaimRejected(event(ClaimType.CANCEL));

        verify(orderItem, never()).changeStatus(any());
        verify(orderService, never()).recalculateStatus(anyLong());
    }
}

package com.zslab.mall.refund.handler;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.refund.service.RefundService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ClaimPickedUpHandler} 단위 검증(D-98 Q2·Q5). RETURN 한정 RefundService.initiate 자동 트리거·CANCEL/EXCHANGE skip·
 * initiate 예외 시 NotificationService.recordRefundFailed 위임(D-96 Q3)을 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimPickedUpHandlerTest {

    private static final Long CLAIM_ID = 1L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final long ITEM_TOTAL_PRICE = 12_000L;
    private static final LocalDateTime PICKED_UP_AT = LocalDateTime.of(2026, 6, 29, 11, 0);

    @Mock
    private RefundService refundService;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private ClaimPickedUpHandler handler;

    private ClaimPickedUp event(ClaimType type) {
        return new ClaimPickedUp(CLAIM_ID, "clm_x", ORDER_ITEM_ID, type, PICKED_UP_AT, PICKED_UP_AT);
    }

    @Test
    @DisplayName("handle: RETURN → initiate(claimId, OrderItem.totalPrice) 자동 트리거")
    void return_triggersInitiate() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getTotalPrice()).thenReturn(ITEM_TOTAL_PRICE);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));

        handler.handle(event(ClaimType.RETURN));

        verify(refundService).initiate(CLAIM_ID, ITEM_TOTAL_PRICE);
    }

    @Test
    @DisplayName("handle: CANCEL → 미대상(initiate 없음)")
    void cancel_skips() {
        handler.handle(event(ClaimType.CANCEL));

        verify(refundService, never()).initiate(anyLong(), anyLong());
    }

    @Test
    @DisplayName("handle: EXCHANGE → 미대상(initiate 없음)")
    void exchange_skips() {
        handler.handle(event(ClaimType.EXCHANGE));

        verify(refundService, never()).initiate(anyLong(), anyLong());
    }

    @Test
    @DisplayName("handle: RETURN·OrderItem 미발견 → 자동 환불 건너뜀")
    void orderItemMissing_skips() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        handler.handle(event(ClaimType.RETURN));

        verify(refundService, never()).initiate(anyLong(), anyLong());
    }

    @Test
    @DisplayName("handle: initiate 예외 → recordRefundFailed 위임·예외 비전파(D-96 Q3)")
    void initiateThrows_recordsRefundFailed() {
        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getTotalPrice()).thenReturn(ITEM_TOTAL_PRICE);
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
        ClaimPickedUp event = event(ClaimType.RETURN);
        when(refundService.initiate(CLAIM_ID, ITEM_TOTAL_PRICE)).thenThrow(new RuntimeException("PG 장애"));

        handler.handle(event);

        verify(notificationService).recordRefundFailed(event);
    }
}

package com.zslab.mall.refund.handler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.event.ClaimInspectionPassed;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.payment.gateway.PaymentGatewayException;
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
 * {@link ClaimInspectionPassedHandler} 단위 검증(Track 81-A D-170·구 ClaimPickedUpHandlerTest 대체). 검수 PASS → initiate(totalPrice)·
 * 품목 미발견 skip·initiate 예외 시 recordRefundFailed 위임(D-96 Q3)을 mock 경계에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ClaimInspectionPassedHandlerTest {

    private static final Long CLAIM_ID = 700L;
    private static final Long ORDER_ITEM_ID = 10L;
    private static final long TOTAL_PRICE = 25_000L;

    @Mock
    private RefundService refundService;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ClaimRepository claimRepository;
    @InjectMocks
    private ClaimInspectionPassedHandler handler;

    @org.junit.jupiter.api.BeforeEach
    void stubReturnClaim() {
        // Track 83 D-177: 핸들러가 클레임 유형(RETURN만 환불)을 행에서 판정한다
        Claim claim = Claim.create(ORDER_ITEM_ID, ClaimType.RETURN, "PRODUCT_DEFECT", null, 1L,
                LocalDateTime.of(2026, 9, 17, 9, 0), OrderItemStatus.DELIVERED);
        org.mockito.Mockito.lenient().when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
    }

    private static ClaimInspectionPassed event() {
        return new ClaimInspectionPassed(CLAIM_ID, "clm_x", ORDER_ITEM_ID, true, LocalDateTime.of(2026, 9, 17, 10, 0));
    }

    private static OrderItem orderItem() {
        return OrderItem.create(1L, 1L, 1L, "상품", 1, TOTAL_PRICE, TOTAL_PRICE, 1000);
    }

    @Test
    @DisplayName("EXCHANGE 검수 PASS → 환불 미개시(교환품 발송 대기·D-177)")
    void handle_exchange_skipsRefund() {
        Claim exchange = Claim.create(ORDER_ITEM_ID, ClaimType.EXCHANGE, "PRODUCT_DEFECT", null, 1L,
                LocalDateTime.of(2026, 9, 17, 9, 0), OrderItemStatus.DELIVERED, 2L);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(exchange));

        handler.handle(event());

        verify(refundService, org.mockito.Mockito.never()).initiate(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("PASS → RefundService.initiate(claimId, orderItem.totalPrice) 1회")
    void handle_initiatesRefundWithTotalPrice() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem()));

        handler.handle(event());

        verify(refundService).initiate(CLAIM_ID, TOTAL_PRICE);
        verify(notificationService, never()).recordRefundFailed(any(ClaimInspectionPassed.class));
    }

    @Test
    @DisplayName("주문 품목 미발견 → initiate 미호출·예외 없음")
    void handle_orderItemMissing_skips() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> handler.handle(event())).doesNotThrowAnyException();

        verify(refundService, never()).initiate(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("initiate 예외 → recordRefundFailed 위임·예외 비전파(D-96 Q3)")
    void handle_initiateFails_recordsFailure() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem()));
        doThrow(new PaymentGatewayException("k", "PG_DOWN", "장애")).when(refundService).initiate(CLAIM_ID, TOTAL_PRICE);

        assertThatCode(() -> handler.handle(event())).doesNotThrowAnyException();

        verify(notificationService).recordRefundFailed(any(ClaimInspectionPassed.class));
    }
}

package com.zslab.mall.refund.handler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.payment.gateway.PaymentGatewayException;
import com.zslab.mall.refund.entity.Refund;
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
 * {@link ClaimApprovedHandler} 단위 검증(Mockito·D-94 Q1·Q7·Q8). CANCEL 한정 게이트·OrderItem.totalPrice 인자 도출·
 * PG/도메인 예외 전파 차단(structured log)을 커버한다. 멱등(existsActive)은 RefundService 내부 책임이므로
 * 핸들러 단위에서는 initiate 위임 여부만 검증한다(통합 테스트가 멱등 결과를 검증).
 */
@ExtendWith(MockitoExtension.class)
class ClaimApprovedHandlerTest {

    private static final Long CLAIM_ID = 11L;
    private static final Long ORDER_ITEM_ID = 110L;
    private static final String CLAIM_PID = "clm_track11unit00000000000001";
    private static final long ITEM_TOTAL_PRICE = 12_000L;

    @Mock
    private RefundService refundService;
    @Mock
    private OrderItemRepository orderItemRepository;
    @InjectMocks
    private ClaimApprovedHandler handler;

    private ClaimApproved event(ClaimType type) {
        return new ClaimApproved(CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, type, ClaimStatus.APPROVED, LocalDateTime.now());
    }

    /** total = unit × quantity(ORD-5) 정합 OrderItem(totalPrice=12000). */
    private OrderItem orderItem() {
        return OrderItem.create(1L, 1L, 1L, 1, ITEM_TOTAL_PRICE, ITEM_TOTAL_PRICE);
    }

    @Test
    @DisplayName("T1 CANCEL: OrderItem.totalPrice를 amount로 initiate 1회 호출")
    void cancel_triggersInitiateWithOrderItemTotalPrice() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem()));

        handler.handle(event(ClaimType.CANCEL));

        verify(refundService).initiate(CLAIM_ID, ITEM_TOTAL_PRICE);
    }

    @Test
    @DisplayName("T2 RETURN: 게이트 스킵 → initiate 미호출·OrderItem 미조회")
    void returnType_skipsInitiate() {
        handler.handle(event(ClaimType.RETURN));

        verify(refundService, never()).initiate(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(orderItemRepository, never()).findById(any());
    }

    @Test
    @DisplayName("T2' EXCHANGE: 게이트 스킵 → initiate 미호출")
    void exchangeType_skipsInitiate() {
        handler.handle(event(ClaimType.EXCHANGE));

        verify(refundService, never()).initiate(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("T3 CANCEL·Service 멱등 no-op: initiate가 기존 행 반환해도 1회 호출·예외 미발생")
    void cancel_serviceIdempotentNoOp_stillInvokesOnce() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem()));
        // 핸들러는 멱등을 가드하지 않고 RefundService에 위임한다(Service가 활성 행을 no-op 반환).
        when(refundService.initiate(CLAIM_ID, ITEM_TOTAL_PRICE))
                .thenReturn(Refund.create(CLAIM_ID, 1L, ITEM_TOTAL_PRICE));

        assertThatCode(() -> handler.handle(event(ClaimType.CANCEL))).doesNotThrowAnyException();

        verify(refundService).initiate(CLAIM_ID, ITEM_TOTAL_PRICE);
    }

    @Test
    @DisplayName("T4 CANCEL·PG/도메인 예외: initiate 예외를 catch → 핸들러 밖 전파 차단(structured log)")
    void cancel_initiateThrows_exceptionNotPropagated() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem()));
        when(refundService.initiate(eq(CLAIM_ID), eq(ITEM_TOTAL_PRICE)))
                .thenThrow(new PaymentGatewayException("k", "PG_DOWN", "환불 게이트웨이 장애"));

        assertThatCode(() -> handler.handle(event(ClaimType.CANCEL))).doesNotThrowAnyException();

        verify(refundService).initiate(CLAIM_ID, ITEM_TOTAL_PRICE);
    }

    @Test
    @DisplayName("T5 CANCEL·OrderItem 미발견: 방어 차단 → initiate 미호출·예외 미발생")
    void cancel_orderItemMissing_skipsInitiate() {
        when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> handler.handle(event(ClaimType.CANCEL))).doesNotThrowAnyException();

        verify(refundService, never()).initiate(any(), org.mockito.ArgumentMatchers.anyLong());
    }
}

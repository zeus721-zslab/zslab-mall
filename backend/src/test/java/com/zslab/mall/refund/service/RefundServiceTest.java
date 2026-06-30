package com.zslab.mall.refund.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.gateway.MockRefundResponse;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.gateway.PaymentGatewayException;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.event.RefundCompleted;
import com.zslab.mall.refund.exception.RefundIdempotentNoOpException;
import com.zslab.mall.refund.exception.RefundInvariantViolationException;
import com.zslab.mall.refund.repository.RefundRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link RefundService} 단위 검증(Mockito). initiate(CLM-3·PAY-1 사전·D-67)·markCompleted(RFN-1·RFN-3·PAY-1 사후·D-70·이벤트)·
 * markFailed(전이·멱등)를 11 케이스로 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final Long CLAIM_ID = 7L;
    private static final Long ORDER_ITEM_ID = 70L;
    private static final Long ORDER_ID = 700L;
    private static final Long PAYMENT_ID = 9L;
    private static final Long REFUND_ID = 33L;
    private static final long REFUND_AMOUNT = 6_000L;
    private static final long PAYMENT_AMOUNT = 10_000L;
    private static final String PG_REFUND_ID = "mock_rfn_0000000000000000000001";
    private static final String PG_TID = "tid_pay_track5";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 6, 28, 9, 0);

    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private RefundService refundService;

    private Claim claim(ClaimStatus status) {
        Claim claim = Claim.create(ORDER_ITEM_ID, ClaimType.CANCEL, "CHANGE_MIND", null, 1L, BASE_TIME);
        ReflectionTestUtils.setField(claim, "id", CLAIM_ID);
        ReflectionTestUtils.setField(claim, "status", status);
        return claim;
    }

    private Payment paidPayment(long amount) {
        Payment payment = Payment.create(ORDER_ID, PaymentMethod.CARD, amount, "pat_track5test0000000000000001", BASE_TIME.plusMinutes(30));
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.PAID);
        ReflectionTestUtils.setField(payment, "pgTid", PG_TID);
        return payment;
    }

    private Refund pendingRefund() {
        Refund refund = Refund.create(CLAIM_ID, PAYMENT_ID, REFUND_AMOUNT);
        ReflectionTestUtils.setField(refund, "id", REFUND_ID);
        refund.assignPgRefundId(PG_REFUND_ID);
        return refund;
    }

    private void stubPaymentGraph(long paymentAmount) {
        when(orderItemRepository.findOrderIdById(ORDER_ITEM_ID)).thenReturn(Optional.of(ORDER_ID));
        when(paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(ORDER_ID, PaymentStatus.PAID))
                .thenReturn(Optional.of(paidPayment(paymentAmount)));
    }

    // ---------- initiate ----------

    @Test
    @DisplayName("initiate: Claim 미승인(REQUESTED) → ClaimInvalidStateException(CLM-3)")
    void initiate_claimNotApproved_blocked() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.REQUESTED)));

        assertThatThrownBy(() -> refundService.initiate(CLAIM_ID, REFUND_AMOUNT))
                .isInstanceOf(ClaimInvalidStateException.class);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiate: PAY-1 사전 누적 초과 → RefundInvariantViolationException")
    void initiate_payOnePreCheckExceeded_blocked() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.APPROVED)));
        stubPaymentGraph(PAYMENT_AMOUNT);
        when(refundRepository.sumCompletedByPaymentId(PAYMENT_ID)).thenReturn(5_000L); // 5000 + 6000 > 10000

        assertThatThrownBy(() -> refundService.initiate(CLAIM_ID, REFUND_AMOUNT))
                .isInstanceOf(RefundInvariantViolationException.class);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiate: PG 호출 예외 → FAILED 전이(D-67·CR-03)")
    void initiate_gatewayException_transitionsToFailed() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.APPROVED)));
        stubPaymentGraph(PAYMENT_AMOUNT);
        when(refundRepository.sumCompletedByPaymentId(PAYMENT_ID)).thenReturn(0L);
        when(refundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentGateway.refund(any(), any()))
                .thenThrow(new PaymentGatewayException("k", "PG_DOWN", "네트워크 오류"));

        Refund result = refundService.initiate(CLAIM_ID, REFUND_AMOUNT);

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    @DisplayName("initiate: 활성 Refund(PENDING) 존재 → 멱등 no-op·기존 행 반환·save 미호출(D-94 Q6)")
    void initiate_activeRefundExists_idempotentNoOp() {
        Refund existing = pendingRefund();
        when(refundRepository.existsActiveByClaimId(CLAIM_ID)).thenReturn(true);
        when(refundRepository.findByClaimId(CLAIM_ID)).thenReturn(java.util.List.of(existing));

        Refund result = refundService.initiate(CLAIM_ID, REFUND_AMOUNT);

        assertThat(result).isSameAs(existing);
        verify(refundRepository, never()).save(any());
        verify(claimRepository, never()).findById(any());
    }

    @Test
    @DisplayName("initiate: 정상 → PENDING 생성·pg_refund_id 부여")
    void initiate_success_pendingWithPgRefundId() {
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.APPROVED)));
        stubPaymentGraph(PAYMENT_AMOUNT);
        when(refundRepository.sumCompletedByPaymentId(PAYMENT_ID)).thenReturn(0L);
        when(refundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentGateway.refund(any(), any())).thenReturn(new MockRefundResponse(PG_REFUND_ID, true, null));

        Refund result = refundService.initiate(CLAIM_ID, REFUND_AMOUNT);

        assertThat(result.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(result.getPgRefundId()).isEqualTo(PG_REFUND_ID);
        assertThat(result.getClaimId()).isEqualTo(CLAIM_ID);
        assertThat(result.getPaymentId()).isEqualTo(PAYMENT_ID);
    }

    // ---------- markCompleted ----------

    @Test
    @DisplayName("markCompleted: pg_refund_id NULL/blank → RefundInvariantViolationException(RFN-1)")
    void markCompleted_nullPgRefundId_blocked() {
        assertThatThrownBy(() -> refundService.markCompleted(null))
                .isInstanceOf(RefundInvariantViolationException.class);
        assertThatThrownBy(() -> refundService.markCompleted("   "))
                .isInstanceOf(RefundInvariantViolationException.class);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("markCompleted: 이미 COMPLETED 중복 콜백 → RefundIdempotentNoOpException(RFN-3)")
    void markCompleted_duplicate_idempotentSignal() {
        Refund refund = pendingRefund();
        ReflectionTestUtils.setField(refund, "status", RefundStatus.COMPLETED);
        when(refundRepository.findByPgRefundId(PG_REFUND_ID)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> refundService.markCompleted(PG_REFUND_ID))
                .isInstanceOf(RefundIdempotentNoOpException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("markCompleted: PAY-1 사후 재검증 초과 → RefundInvariantViolationException(D-68)")
    void markCompleted_payOnePostCheckExceeded_blocked() {
        Refund refund = pendingRefund();
        when(refundRepository.findByPgRefundId(PG_REFUND_ID)).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(paidPayment(PAYMENT_AMOUNT)));
        when(refundRepository.sumCompletedByPaymentId(PAYMENT_ID)).thenReturn(5_000L); // 5000 + 6000 > 10000

        assertThatThrownBy(() -> refundService.markCompleted(PG_REFUND_ID))
                .isInstanceOf(RefundInvariantViolationException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("markCompleted: refunded_at = 시스템 시각(D-70·콜백 시각 아님)")
    void markCompleted_refundedAt_systemClock() {
        Refund refund = pendingRefund();
        when(refundRepository.findByPgRefundId(PG_REFUND_ID)).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(paidPayment(PAYMENT_AMOUNT)));
        when(refundRepository.sumCompletedByPaymentId(PAYMENT_ID)).thenReturn(0L);
        when(refundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        Refund result = refundService.markCompleted(PG_REFUND_ID);
        LocalDateTime after = LocalDateTime.now();

        assertThat(result.getRefundedAt()).isNotNull();
        assertThat(result.getRefundedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("markCompleted: 정상 → COMPLETED 전이·RefundCompleted 발행(D-29)")
    void markCompleted_success_completesAndPublishes() {
        Refund refund = pendingRefund();
        when(refundRepository.findByPgRefundId(PG_REFUND_ID)).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(paidPayment(PAYMENT_AMOUNT)));
        when(refundRepository.sumCompletedByPaymentId(PAYMENT_ID)).thenReturn(0L);
        when(refundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Refund result = refundService.markCompleted(PG_REFUND_ID);

        assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        verify(eventPublisher).publishEvent(any(RefundCompleted.class));
    }

    // ---------- markFailed ----------

    @Test
    @DisplayName("markFailed: PENDING → FAILED 전이")
    void markFailed_pending_transitionsToFailed() {
        Refund refund = pendingRefund();
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund));
        when(refundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Refund result = refundService.markFailed(REFUND_ID, "PG_FAILURE");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    @DisplayName("markFailed: 이미 FAILED → 멱등 NO-OP(미저장)")
    void markFailed_alreadyFailed_idempotent() {
        Refund refund = pendingRefund();
        ReflectionTestUtils.setField(refund, "status", RefundStatus.FAILED);
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund));

        Refund result = refundService.markFailed(REFUND_ID, "PG_FAILURE");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        verify(refundRepository, never()).save(any());
    }
}

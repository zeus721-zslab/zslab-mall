package com.zslab.mall.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.refund.repository.RefundRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link PaymentService#markCancelled} 단위 검증(Track 5·D-71·PAY-2). 전액 환불 일치 전이·부분환불 유지·멱등 3 케이스.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceMarkCancelledTest {

    private static final Long PAYMENT_ID = 9L;
    private static final Long ORDER_ID = 700L;
    private static final long PAYMENT_AMOUNT = 10_000L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private TracedEventPublisher eventPublisher;
    @Mock
    private RefundRepository refundRepository;
    @InjectMocks
    private PaymentService paymentService;

    private Payment payment(PaymentStatus status) {
        Payment payment = Payment.create(
                ORDER_ID, PaymentMethod.CARD, PAYMENT_AMOUNT, "pat_markcancel000000000000001", LocalDateTime.now().plusMinutes(30));
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    @Test
    @DisplayName("누적 환불 == 결제액(전액) → PAID → CANCELLED 전이(D-71)")
    void fullRefund_cancels() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PAID)));
        when(refundRepository.sumCompletedByPaymentId(PAYMENT_ID)).thenReturn(PAYMENT_AMOUNT);

        paymentService.markCancelled(PAYMENT_ID);

        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("누적 환불 < 결제액(부분) → 상태 유지(no-op·D-71)")
    void partialRefund_holdsStatus() {
        Payment payment = payment(PaymentStatus.PAID);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(refundRepository.sumCompletedByPaymentId(PAYMENT_ID)).thenReturn(7_000L);

        paymentService.markCancelled(PAYMENT_ID);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 CANCELLED → 멱등 NO-OP(누적 계산·저장 없음)")
    void alreadyCancelled_idempotent() {
        Payment payment = payment(PaymentStatus.CANCELLED);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        paymentService.markCancelled(PAYMENT_ID);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(refundRepository, never()).sumCompletedByPaymentId(any());
        verify(paymentRepository, never()).save(any());
    }
}

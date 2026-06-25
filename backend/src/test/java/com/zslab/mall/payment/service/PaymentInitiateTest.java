package com.zslab.mall.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.payment.command.PaymentInitiateRequest;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.exception.PaymentAlreadyCompletedException;
import com.zslab.mall.payment.exception.PaymentInProgressException;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link PaymentService#initiate} 검증(Mockito·D-28·D-31·D-32). 정상·PAY-3a 차단·PENDING TTL 차단·만료 후 허용.
 */
@ExtendWith(MockitoExtension.class)
class PaymentInitiateTest {

    private static final Long ORDER_ID = 100L;
    private static final Long AMOUNT = 10_000L;

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private PaymentService paymentService;

    private PaymentInitiateRequest request() {
        return new PaymentInitiateRequest(ORDER_ID, PaymentMethod.CARD, AMOUNT);
    }

    @Test
    @DisplayName("initiate: 정상 → PENDING 행 생성·attempt_key(pat_)·expires_at 설정·PG 등록")
    void initiate_happyPath() {
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);
        when(paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(ORDER_ID, PaymentStatus.PENDING))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = paymentService.initiate(request());

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(result.getPaymentAttemptKey()).startsWith("pat_").hasSize(30);
        assertThat(result.getExpiresAt()).isNotNull().isAfter(LocalDateTime.now());
        verify(paymentRepository).save(any(Payment.class));
        verify(paymentGateway).requestPayment(anyString(), eq(AMOUNT), eq(PaymentMethod.CARD));
    }

    @Test
    @DisplayName("initiate: 이미 PAID 행 존재 → PaymentAlreadyCompletedException(PAY-3a)")
    void initiate_blockedByPaid() {
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(true);

        assertThatThrownBy(() -> paymentService.initiate(request()))
                .isInstanceOf(PaymentAlreadyCompletedException.class);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiate: 미만료 PENDING 존재 → PaymentInProgressException(D-32)")
    void initiate_blockedByActivePending() {
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);
        Payment activePending = Payment.create(
                ORDER_ID, PaymentMethod.CARD, AMOUNT, "pat_ACTIVE0000000000000000AAAA", LocalDateTime.now().plusMinutes(30));
        when(paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(ORDER_ID, PaymentStatus.PENDING))
                .thenReturn(Optional.of(activePending));

        assertThatThrownBy(() -> paymentService.initiate(request()))
                .isInstanceOf(PaymentInProgressException.class);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiate: 만료 PENDING 존재 → 차단 해제·새 행 생성 허용(D-32)")
    void initiate_allowedAfterExpiredPending() {
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);
        Payment expiredPending = Payment.create(
                ORDER_ID, PaymentMethod.CARD, AMOUNT, "pat_EXPIRED000000000000000AAAA", LocalDateTime.now().minusMinutes(1));
        when(paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(ORDER_ID, PaymentStatus.PENDING))
                .thenReturn(Optional.of(expiredPending));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = paymentService.initiate(request());

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("initiate: 입력 누락 → IllegalArgumentException")
    void initiate_invalidInput_throws() {
        assertThatThrownBy(() -> paymentService.initiate(new PaymentInitiateRequest(null, PaymentMethod.CARD, AMOUNT)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

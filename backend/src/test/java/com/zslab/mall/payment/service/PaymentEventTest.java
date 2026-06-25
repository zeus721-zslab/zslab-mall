package com.zslab.mall.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.CallbackType;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.event.PaymentCompleted;
import com.zslab.mall.payment.event.PaymentFailed;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link PaymentService} 이벤트 발행 페이로드·발행 시점 검증(D-29·D-30). 페이로드 정합 + REJECT 예외 시 미발행.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventTest {

    private static final Long ORDER_ID = 100L;
    private static final Long PAYMENT_ID = 7L;
    private static final Long AMOUNT = 10_000L;
    private static final String ATTEMPT_KEY = "pat_EVENT00000000000000000000AA";
    private static final String PG_PROVIDER = "MOCK_PG";
    private static final String PG_TID = "tid_evt";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 26, 11, 0);

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private PaymentService paymentService;

    private Payment paymentInStatus(PaymentStatus status) {
        Payment payment = Payment.create(ORDER_ID, PaymentMethod.CARD, AMOUNT, ATTEMPT_KEY, OCCURRED_AT.plusMinutes(30));
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    private PaymentCallbackCommand command(CallbackType type, Map<String, String> metadata) {
        return new PaymentCallbackCommand(PG_PROVIDER, type, ATTEMPT_KEY, PG_TID, OCCURRED_AT, metadata);
    }

    @Test
    @DisplayName("PaymentCompleted 페이로드: paymentId·orderId·amount·pgTransactionId·occurredAt 정합(D-30)")
    void paymentCompleted_payload() {
        Payment payment = paymentInStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByPaymentAttemptKey(ATTEMPT_KEY)).thenReturn(Optional.of(payment));
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);

        paymentService.handleCallback(command(CallbackType.SUCCESS, Map.of()));

        ArgumentCaptor<PaymentCompleted> captor = ArgumentCaptor.forClass(PaymentCompleted.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PaymentCompleted event = captor.getValue();
        assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.amount()).isEqualTo(AMOUNT);
        assertThat(event.pgTransactionId()).isEqualTo(PG_TID);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    @DisplayName("PaymentFailed 페이로드: failureCode는 metadata에서 추출·occurredAt 정합(D-30)")
    void paymentFailed_payload_fromMetadata() {
        Payment payment = paymentInStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByPaymentAttemptKey(ATTEMPT_KEY)).thenReturn(Optional.of(payment));

        paymentService.handleCallback(command(CallbackType.FAILURE, Map.of("failureCode", "INSUFFICIENT_BALANCE")));

        ArgumentCaptor<PaymentFailed> captor = ArgumentCaptor.forClass(PaymentFailed.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PaymentFailed event = captor.getValue();
        assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    @DisplayName("PaymentFailed: metadata에 failureCode 없으면 기본값(PG_FAILURE)")
    void paymentFailed_defaultCode() {
        Payment payment = paymentInStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByPaymentAttemptKey(ATTEMPT_KEY)).thenReturn(Optional.of(payment));

        paymentService.handleCallback(command(CallbackType.FAILURE, null));

        assertThat(payment.getFailureCode()).isEqualTo("PG_FAILURE");
        verify(eventPublisher).publishEvent(any(PaymentFailed.class));
    }

    @Test
    @DisplayName("REJECT 예외 시 이벤트 미발행(예외가 pull·publish 이전에 전파·롤백 안전)")
    void reject_doesNotPublish() {
        Payment payment = paymentInStatus(PaymentStatus.FAILED);
        when(paymentRepository.findByPaymentAttemptKey(ATTEMPT_KEY)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.handleCallback(command(CallbackType.SUCCESS, Map.of())))
                .isInstanceOf(RuntimeException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }
}

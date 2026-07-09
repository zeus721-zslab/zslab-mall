package com.zslab.mall.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.service.OrderAutoCancelService;
import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.CallbackType;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.event.PaymentCompleted;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 콜백 멱등성 검증(D-31). 동일 콜백 2회 수신 시 attempt_key 1차 키로 같은 행을 식별해 1회만 상태 전이·발행한다.
 *
 * <p>DB 레벨 유일성((pg_provider,pg_tid)·payment_attempt_key UNIQUE 충돌 INSERT 차단·PAY-3b)은
 * {@code PaymentRepositoryTest}(@DataJpaTest·실 DB)에서 검증한다 — 본 단위 테스트는 Service 멱등 로직만 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentIdempotencyTest {

    private static final Long ORDER_ID = 100L;
    private static final Long AMOUNT = 10_000L;
    private static final String ATTEMPT_KEY = "pat_IDEM00000000000000000000AAAA";
    private static final String PG_PROVIDER = "MOCK_PG";
    private static final String PG_TID = "tid_idem";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 26, 12, 0);

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private TracedEventPublisher eventPublisher;
    @Mock
    private OrderAutoCancelService orderAutoCancelService;
    @InjectMocks
    private PaymentService paymentService;

    private Payment pendingPayment() {
        Payment payment = Payment.create(ORDER_ID, PaymentMethod.CARD, AMOUNT, ATTEMPT_KEY, OCCURRED_AT.plusMinutes(30));
        ReflectionTestUtils.setField(payment, "id", 7L);
        return payment;
    }

    private PaymentCallbackCommand command(CallbackType type) {
        return new PaymentCallbackCommand(PG_PROVIDER, type, ATTEMPT_KEY, PG_TID, OCCURRED_AT, Map.of());
    }

    @Test
    @DisplayName("SUCCESS 2회 수신: 1회 PAID 전이·발행, 2회차는 멱등 NO-OP(발행 1회뿐)")
    void duplicateSuccess_isIdempotent() {
        Payment payment = pendingPayment();
        // 동일 행을 두 콜백 모두에 반환(attempt_key 1차 키 식별)
        when(paymentRepository.findByPaymentAttemptKey(ATTEMPT_KEY)).thenReturn(Optional.of(payment));
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);

        paymentService.handleCallback(command(CallbackType.SUCCESS)); // PENDING→PAID·발행
        paymentService.handleCallback(command(CallbackType.SUCCESS)); // PAID·NO-OP

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(eventPublisher, times(1)).publishEvent(any(PaymentCompleted.class));
    }

    @Test
    @DisplayName("FAILURE 2회 수신: 1회 PG 실패(FAILED·Order 종료 위임), 2회차는 NO-OP(cancelOne 1회뿐·FE-12c 정정)")
    void duplicateFailure_isIdempotent() {
        Payment payment = pendingPayment();
        when(paymentRepository.findByPaymentAttemptKey(ATTEMPT_KEY)).thenReturn(Optional.of(payment));

        paymentService.handleCallback(command(CallbackType.FAILURE)); // PENDING→FAILED·Order 종료 위임
        paymentService.handleCallback(command(CallbackType.FAILURE)); // FAILED(비PENDING)·NO-OP

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(orderAutoCancelService, times(1)).cancelOne(ORDER_ID);   // 1회차만 위임(2회차 FAILED는 skip)
        verify(eventPublisher, never()).publishEvent(any());           // PaymentFailed 미발행
    }
}

package com.zslab.mall.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link PaymentService#handleCallback} 콜백 타입 × 상태 매트릭스 12 케이스 전건 검증(D-34).
 */
@ExtendWith(MockitoExtension.class)
class PaymentCallbackTest {

    private static final Long ORDER_ID = 100L;
    private static final Long PAYMENT_ID = 7L;
    private static final Long AMOUNT = 10_000L;
    private static final String ATTEMPT_KEY = "pat_CALLBACK0000000000000000AA";
    private static final String PG_PROVIDER = "MOCK_PG";
    private static final String PG_TID = "tid_xyz";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 6, 26, 10, 0);

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private TracedEventPublisher eventPublisher;
    @Mock
    private OrderAutoCancelService orderAutoCancelService;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private EntityManager entityManager;
    @InjectMocks
    private PaymentService paymentService;

    private Payment paymentInStatus(PaymentStatus status) {
        Payment payment = Payment.create(
                ORDER_ID, PaymentMethod.CARD, AMOUNT, ATTEMPT_KEY, OCCURRED_AT.plusMinutes(30));
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }


    /** D-173: SUCCESS 승인 전 Order 행 락 재확인용 PENDING_PAYMENT 주문(생성 직후 상태). */
    private Order pendingOrder() {
        Order order = Order.create(1L, "20260917-ABCDEF", 0L, 0L);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    private PaymentCallbackCommand command(CallbackType type) {
        return new PaymentCallbackCommand(PG_PROVIDER, type, ATTEMPT_KEY, PG_TID, OCCURRED_AT, Map.of());
    }

    private void stubFind(Payment payment) {
        when(paymentRepository.findByPaymentAttemptKey(ATTEMPT_KEY)).thenReturn(Optional.of(payment));
    }

    // ---------- SUCCESS ----------

    @Test
    @DisplayName("SUCCESS × PENDING → PAID 전이·PaymentCompleted 발행")
    void success_pending_completes() {
        Payment payment = paymentInStatus(PaymentStatus.PENDING);
        stubFind(payment);
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(pendingOrder()));

        paymentService.handleCallback(command(CallbackType.SUCCESS));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(eventPublisher).publishEvent(any(PaymentCompleted.class));
    }

    @Test
    @DisplayName("SUCCESS × PENDING + 이미 PAID 행 존재 → InvalidCallbackException(PAY-3a anomaly)")
    void success_pending_payThreeAViolation_rejects() {
        Payment payment = paymentInStatus(PaymentStatus.PENDING);
        stubFind(payment);
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(true);

        assertThatThrownBy(() -> paymentService.handleCallback(command(CallbackType.SUCCESS)))
                .isInstanceOf(InvalidCallbackException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("SUCCESS × PENDING + 주문 비PENDING_PAYMENT(락 후 재확인·늦은 승인) → InvalidCallbackException·Payment PENDING 유지·미발행(D-173)")
    void success_pending_orderAlreadyTerminated_rejectsBeforeComplete() {
        Payment payment = paymentInStatus(PaymentStatus.PENDING);
        stubFind(payment);
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);
        Order expiredOrder = pendingOrder();
        ReflectionTestUtils.setField(expiredOrder, "status", OrderStatus.PAYMENT_EXPIRED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(expiredOrder));

        assertThatThrownBy(() -> paymentService.handleCallback(command(CallbackType.SUCCESS)))
                .isInstanceOf(InvalidCallbackException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        InOrder lockOrder = inOrder(entityManager);
        lockOrder.verify(entityManager).refresh(same(payment), eq(LockModeType.PESSIMISTIC_WRITE));
        lockOrder.verify(entityManager).refresh(same(expiredOrder), eq(LockModeType.PESSIMISTIC_WRITE));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("SUCCESS × PAID → 멱등 NO-OP·미발행")
    void success_paid_noop() {
        Payment payment = paymentInStatus(PaymentStatus.PAID);
        stubFind(payment);

        paymentService.handleCallback(command(CallbackType.SUCCESS));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELLED", "EXPIRED"})
    @DisplayName("SUCCESS × 종결(FAILED·CANCELLED·EXPIRED) → REJECT(InvalidCallbackException)")
    void success_terminal_rejects(PaymentStatus terminal) {
        Payment payment = paymentInStatus(terminal);
        stubFind(payment);

        assertThatThrownBy(() -> paymentService.handleCallback(command(CallbackType.SUCCESS)))
                .isInstanceOf(InvalidCallbackException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------- FAILURE ----------

    @Test
    @DisplayName("FAILURE × PENDING → PG 실제 실패(Payment FAILED·failure_code) + Order 종료 위임·PaymentFailed 미발행(FE-12c 정정)")
    void failure_pending_fails() {
        Payment payment = paymentInStatus(PaymentStatus.PENDING);
        stubFind(payment);

        paymentService.handleCallback(command(CallbackType.FAILURE));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("PG_FAILURE");   // metadata 미제공 → 기본값
        verify(orderAutoCancelService).cancelOne(ORDER_ID);   // Order PAYMENT_EXPIRED + OrderTerminated 발행 위임
        verify(eventPublisher, never()).publishEvent(any());  // PaymentFailed 미발행
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PAID", "FAILED", "CANCELLED", "EXPIRED"})
    @DisplayName("FAILURE × 비PENDING(PAID·FAILED·CANCELLED·EXPIRED) → NO-OP·미발행")
    void failure_nonPending_noop(PaymentStatus status) {
        Payment payment = paymentInStatus(status);
        stubFind(payment);

        paymentService.handleCallback(command(CallbackType.FAILURE));

        assertThat(payment.getStatus()).isEqualTo(status);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------- CANCEL ----------

    @Test
    @DisplayName("CANCEL × PAID → CANCELLED 전이·미발행(본 트랙 이벤트 없음)")
    void cancel_paid_cancels() {
        Payment payment = paymentInStatus(PaymentStatus.PAID);
        stubFind(payment);

        paymentService.handleCallback(command(CallbackType.CANCEL));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("CANCEL × PENDING → 미결제 종료(결제창 취소·Payment EXPIRED + Order 종료 위임)·PaymentFailed 미발행(FE-12c)")
    void cancel_pending_terminates() {
        Payment payment = paymentInStatus(PaymentStatus.PENDING);
        stubFind(payment);

        paymentService.handleCallback(command(CallbackType.CANCEL));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        verify(orderAutoCancelService).cancelOne(ORDER_ID);   // Order PAYMENT_EXPIRED + OrderTerminated 발행 위임
        verify(eventPublisher, never()).publishEvent(any());  // Payment는 이벤트 미발행(expire)
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELLED", "EXPIRED"})
    @DisplayName("CANCEL × 종결(FAILED·CANCELLED·EXPIRED) → NO-OP·미발행")
    void cancel_terminal_noop(PaymentStatus terminal) {
        Payment payment = paymentInStatus(terminal);
        stubFind(payment);

        paymentService.handleCallback(command(CallbackType.CANCEL));

        assertThat(payment.getStatus()).isEqualTo(terminal);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------- 행 미발견 ----------

    @Test
    @DisplayName("attempt_key 미매칭 → InvalidCallbackException")
    void callback_attemptKeyNotFound_rejects() {
        when(paymentRepository.findByPaymentAttemptKey(ATTEMPT_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.handleCallback(command(CallbackType.SUCCESS)))
                .isInstanceOf(InvalidCallbackException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }
}

package com.zslab.mall.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link PaymentService#initiate} 검증(Mockito·D-28·D-31·D-32·D-56·D-61). 정상·본인 불일치 404·PAY-3a·PENDING TTL·만료 후 허용.
 *
 * <p>D-56로 시그니처가 {@code initiate(orderPublicId, buyerId, method)}로 변경됐다. amount는 Order에서 서버 재계산(D-61)하며,
 * 본 단위 테스트는 실제 Order(total_price=AMOUNT)를 mock Repository로 주입해 본인 검증·amount 산정·결제 생성 경로를 검증한다.
 * D-60 상품·재고 재검증은 CheckoutService 책임(D-63)이라 본 테스트 범위 밖이다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentInitiateTest {

    private static final String ORDER_PUBLIC_ID = "ord_01J0000000000000000000TEST";
    private static final Long ORDER_ID = 100L;
    private static final Long BUYER_ID = 1L;
    private static final Long AMOUNT = 10_000L;
    private static final String REDIRECT_URL = "https://mock-pg.zslab.local/checkout?attemptKey=pat_x";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private TracedEventPublisher eventPublisher;
    @InjectMocks
    private PaymentService paymentService;

    /** total_price=AMOUNT(=unit 5,000 × 2)·discount=0·shipping=0·id=ORDER_ID인 실제 Order(amount 재계산 = AMOUNT). */
    private Order order(Long buyerId) {
        Order order = Order.create(buyerId, "20260627-INIT01", 0L, 0L);
        order.addItem(OrderItem.create(1L, 1L, 1L, 2, 5_000L, 10_000L));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    @Test
    @DisplayName("initiate: 정상 → PENDING 행 생성·amount 서버 재계산·attempt_key(pat_)·redirectUrl 반환")
    void initiate_happyPath() {
        when(orderRepository.findByPublicId(ORDER_PUBLIC_ID)).thenReturn(Optional.of(order(BUYER_ID)));
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);
        when(paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(ORDER_ID, PaymentStatus.PENDING))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.requestPayment(anyString(), eq(AMOUNT), eq(PaymentMethod.CARD))).thenReturn(REDIRECT_URL);

        PaymentInitiation result = paymentService.initiate(ORDER_PUBLIC_ID, BUYER_ID, PaymentMethod.CARD);

        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.payment().getOrderId()).isEqualTo(ORDER_ID);
        assertThat(result.payment().getAmount()).isEqualTo(AMOUNT);
        assertThat(result.payment().getPaymentAttemptKey()).startsWith("pat_").hasSize(30);
        assertThat(result.payment().getExpiresAt()).isNotNull().isAfter(LocalDateTime.now());
        assertThat(result.redirectUrl()).isEqualTo(REDIRECT_URL);
        verify(paymentRepository).save(any(Payment.class));
        verify(paymentGateway).requestPayment(anyString(), eq(AMOUNT), eq(PaymentMethod.CARD));
    }

    @Test
    @DisplayName("initiate: 주문 미존재 → OrderNotFoundException(404)")
    void initiate_orderNotFound() {
        when(orderRepository.findByPublicId(ORDER_PUBLIC_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.initiate(ORDER_PUBLIC_ID, BUYER_ID, PaymentMethod.CARD))
                .isInstanceOf(OrderNotFoundException.class);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiate: 타인 주문 → OrderNotFoundException(404·정보 노출 회피)")
    void initiate_notOwner() {
        when(orderRepository.findByPublicId(ORDER_PUBLIC_ID)).thenReturn(Optional.of(order(999L)));

        assertThatThrownBy(() -> paymentService.initiate(ORDER_PUBLIC_ID, BUYER_ID, PaymentMethod.CARD))
                .isInstanceOf(OrderNotFoundException.class);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiate: 이미 PAID 행 존재 → PaymentAlreadyCompletedException(PAY-3a)")
    void initiate_blockedByPaid() {
        when(orderRepository.findByPublicId(ORDER_PUBLIC_ID)).thenReturn(Optional.of(order(BUYER_ID)));
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(true);

        assertThatThrownBy(() -> paymentService.initiate(ORDER_PUBLIC_ID, BUYER_ID, PaymentMethod.CARD))
                .isInstanceOf(PaymentAlreadyCompletedException.class);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiate: 미만료 PENDING 존재 → PaymentInProgressException(D-32)")
    void initiate_blockedByActivePending() {
        when(orderRepository.findByPublicId(ORDER_PUBLIC_ID)).thenReturn(Optional.of(order(BUYER_ID)));
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);
        Payment activePending = Payment.create(
                ORDER_ID, PaymentMethod.CARD, AMOUNT, "pat_ACTIVE0000000000000000AAAA", LocalDateTime.now().plusMinutes(30));
        when(paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(ORDER_ID, PaymentStatus.PENDING))
                .thenReturn(Optional.of(activePending));

        assertThatThrownBy(() -> paymentService.initiate(ORDER_PUBLIC_ID, BUYER_ID, PaymentMethod.CARD))
                .isInstanceOf(PaymentInProgressException.class);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiate: 만료 PENDING 존재 → 차단 해제·새 행 생성 허용(D-32)")
    void initiate_allowedAfterExpiredPending() {
        when(orderRepository.findByPublicId(ORDER_PUBLIC_ID)).thenReturn(Optional.of(order(BUYER_ID)));
        when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).thenReturn(false);
        Payment expiredPending = Payment.create(
                ORDER_ID, PaymentMethod.CARD, AMOUNT, "pat_EXPIRED000000000000000AAAA", LocalDateTime.now().minusMinutes(1));
        when(paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(ORDER_ID, PaymentStatus.PENDING))
                .thenReturn(Optional.of(expiredPending));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.requestPayment(anyString(), eq(AMOUNT), eq(PaymentMethod.CARD))).thenReturn(REDIRECT_URL);

        PaymentInitiation result = paymentService.initiate(ORDER_PUBLIC_ID, BUYER_ID, PaymentMethod.CARD);

        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("initiate: 입력 누락(orderPublicId null) → IllegalArgumentException")
    void initiate_invalidInput_throws() {
        assertThatThrownBy(() -> paymentService.initiate(null, BUYER_ID, PaymentMethod.CARD))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

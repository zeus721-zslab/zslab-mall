package com.zslab.mall.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.enums.PaymentStatus;
import com.zslab.mall.payment.event.PaymentCompleted;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link Payment} 도메인 메서드(create·complete·fail·cancel·isExpired·pullDomainEvents) 및 상태 전이·이벤트 누적 검증.
 */
class PaymentTest {

    private static final Long ORDER_ID = 100L;
    private static final Long PAYMENT_ID = 7L;
    private static final Long AMOUNT = 10_000L;
    private static final String ATTEMPT_KEY = "pat_TESTKEY0000000000000000AA";
    private static final String PG_PROVIDER = "MOCK_PG";
    private static final String PG_TID = "tid_abc123";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 26, 9, 0);

    private Payment pendingPayment() {
        Payment payment = Payment.create(ORDER_ID, PaymentMethod.CARD, AMOUNT, ATTEMPT_KEY, NOW.plusMinutes(30));
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    @Test
    @DisplayName("create: 초기 상태 PENDING·필드 설정·이벤트 없음")
    void create_initialState() {
        Payment payment = Payment.create(ORDER_ID, PaymentMethod.KAKAO, AMOUNT, ATTEMPT_KEY, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(payment.getMethod()).isEqualTo(PaymentMethod.KAKAO);
        assertThat(payment.getAmount()).isEqualTo(AMOUNT);
        assertThat(payment.getPaymentAttemptKey()).isEqualTo(ATTEMPT_KEY);
        assertThat(payment.getDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("create: 필수값 누락·금액 1 미만 → IllegalArgumentException")
    void create_invalidInput_throws() {
        assertThatThrownBy(() -> Payment.create(null, PaymentMethod.CARD, AMOUNT, ATTEMPT_KEY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.create(ORDER_ID, PaymentMethod.CARD, AMOUNT, "  ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.create(ORDER_ID, PaymentMethod.CARD, 0L, ATTEMPT_KEY, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("결제금액");
    }

    @Test
    @DisplayName("complete: PENDING→PAID·필드 설정·PaymentCompleted 누적(페이로드 정합)")
    void complete_transitionsAndAccumulatesEvent() {
        Payment payment = pendingPayment();

        payment.complete(NOW, PG_PROVIDER, PG_TID);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(payment.getPgProvider()).isEqualTo(PG_PROVIDER);
        assertThat(payment.getPgTid()).isEqualTo(PG_TID);
        assertThat(payment.getDomainEvents()).hasSize(1);
        assertThat(payment.getDomainEvents().get(0)).isInstanceOf(PaymentCompleted.class);
        PaymentCompleted event = (PaymentCompleted) payment.getDomainEvents().get(0);
        assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.amount()).isEqualTo(AMOUNT);
        assertThat(event.pgTransactionId()).isEqualTo(PG_TID);
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("complete: 종결 상태에서 호출 → IllegalStateException(PAY-2)")
    void complete_fromTerminal_throws() {
        Payment payment = pendingPayment();
        payment.fail("PG_FAILURE"); // PENDING→FAILED

        assertThatThrownBy(() -> payment.complete(NOW, PG_PROVIDER, PG_TID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("불법 결제 상태 전이");
    }

    @Test
    @DisplayName("fail: PENDING→FAILED·failureCode 설정·이벤트 미발행(FE-12c·PaymentFailed 제거)")
    void fail_transitionsWithoutEvent() {
        Payment payment = pendingPayment();

        payment.fail("CARD_DECLINED");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("CARD_DECLINED");
        assertThat(payment.getDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("cancel: PAID→CANCELLED·본 트랙 이벤트 없음")
    void cancel_fromPaid_noEvent() {
        Payment payment = pendingPayment();
        payment.complete(NOW, PG_PROVIDER, PG_TID); // → PAID
        payment.pullDomainEvents(); // PaymentCompleted 비움

        payment.cancel();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("cancel: PENDING에서 호출 → IllegalStateException(PENDING→CANCELLED 불법)")
    void cancel_fromPending_throws() {
        Payment payment = pendingPayment();
        assertThatThrownBy(payment::cancel)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("expire: PENDING→EXPIRED·이벤트 없음(FE-12c·PaymentFailed 미발행)")
    void expire_fromPending_noEvent() {
        Payment payment = pendingPayment();

        payment.expire();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(payment.getDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("expire: 종결 상태에서 호출 → IllegalStateException(PAY-2)")
    void expire_fromTerminal_throws() {
        Payment payment = pendingPayment();
        payment.complete(NOW, PG_PROVIDER, PG_TID); // → PAID

        assertThatThrownBy(payment::expire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("불법 결제 상태 전이");
    }

    @Test
    @DisplayName("isExpired: PENDING+만료시각 경과 true / 미경과·null·비PENDING false")
    void isExpired_judgesByStatusAndTime() {
        Payment payment = Payment.create(ORDER_ID, PaymentMethod.CARD, AMOUNT, ATTEMPT_KEY, NOW.plusMinutes(30));
        assertThat(payment.isExpired(NOW.plusMinutes(31))).isTrue();
        assertThat(payment.isExpired(NOW.plusMinutes(10))).isFalse();

        Payment noExpiry = Payment.create(ORDER_ID, PaymentMethod.CARD, AMOUNT, ATTEMPT_KEY, null);
        assertThat(noExpiry.isExpired(NOW.plusYears(1))).isFalse();

        Payment paid = pendingPayment();
        paid.complete(NOW, PG_PROVIDER, PG_TID);
        assertThat(paid.isExpired(NOW.plusYears(1))).isFalse(); // 비PENDING은 만료 판정 대상 아님
    }

    @Test
    @DisplayName("pullDomainEvents: 반환 후 내부 목록 비움")
    void pullDomainEvents_returnsAndClears() {
        Payment payment = pendingPayment();
        payment.complete(NOW, PG_PROVIDER, PG_TID);

        assertThat(payment.pullDomainEvents()).hasSize(1);
        assertThat(payment.getDomainEvents()).isEmpty();
        assertThat(payment.pullDomainEvents()).isEmpty();
    }
}

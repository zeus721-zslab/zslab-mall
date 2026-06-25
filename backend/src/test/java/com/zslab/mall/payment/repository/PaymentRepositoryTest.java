package com.zslab.mall.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link PaymentRepository} @DataJpaTest(실 MariaDB). @PrePersist public_id·파생 쿼리 + V3 제약(UNIQUE·CHECK) 검증.
 */
class PaymentRepositoryTest extends PaymentDataJpaTestBase {

    private static final Long ORDER_ID = 100L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 26, 9, 0);

    @Autowired
    private PaymentRepository paymentRepository;

    /** pat_ + 26자 = CHAR(30) 정확 길이 키 생성. */
    private static String attemptKey(String tag) {
        String body = (tag + "0".repeat(26)).substring(0, 26);
        return "pat_" + body;
    }

    @Test
    @DisplayName("save: @PrePersist로 public_id(pay_) 생성·attempt_key 영속·id 할당")
    void save_generatesPublicId() {
        disableForeignKeyChecks();
        Payment saved = paymentRepository.saveAndFlush(buildPayment(ORDER_ID, attemptKey("SAVE")));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPublicId()).startsWith("pay_").hasSize(30);
        assertThat(saved.getPaymentAttemptKey()).isEqualTo(attemptKey("SAVE"));
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("findByPaymentAttemptKey: 영속 후 조회(D-35 1차 키)")
    void findByPaymentAttemptKey() {
        disableForeignKeyChecks();
        paymentRepository.saveAndFlush(buildPayment(ORDER_ID, attemptKey("FIND")));
        entityManager.clear();

        Optional<Payment> found = paymentRepository.findByPaymentAttemptKey(attemptKey("FIND"));

        assertThat(found).isPresent();
        assertThat(found.get().getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("existsByOrderIdAndStatus: PAID 행 존재 판정(PAY-3a)")
    void existsByOrderIdAndStatus() {
        disableForeignKeyChecks();
        Payment paid = buildPayment(ORDER_ID, attemptKey("PAID"));
        paid.complete(NOW, "MOCK_PG", "tid_exists");
        paymentRepository.saveAndFlush(paid);
        entityManager.clear();

        assertThat(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PAID)).isTrue();
        assertThat(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.PENDING)).isFalse();
        assertThat(paymentRepository.existsByOrderIdAndStatus(999L, PaymentStatus.PAID)).isFalse();
    }

    @Test
    @DisplayName("findFirstByOrderIdAndStatusOrderByIdDesc: 동일 주문 최신 PENDING 반환")
    void findFirstPending_returnsLatest() {
        disableForeignKeyChecks();
        paymentRepository.saveAndFlush(buildPayment(ORDER_ID, attemptKey("OLD")));
        Payment newer = paymentRepository.saveAndFlush(buildPayment(ORDER_ID, attemptKey("NEW")));
        entityManager.clear();

        Optional<Payment> found =
                paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(ORDER_ID, PaymentStatus.PENDING);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(newer.getId());
    }

    @Test
    @DisplayName("uk_payment_attempt_key: 동일 attempt_key 중복 INSERT 차단(D-35)")
    void uniqueAttemptKey_violation() {
        disableForeignKeyChecks();
        String dupKey = attemptKey("DUP");
        paymentRepository.saveAndFlush(buildPayment(ORDER_ID, dupKey));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(buildPayment(200L, dupKey)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("uk_payment_provider_pg_tid: 동일 (pg_provider,pg_tid) 중복 차단(PAY-3b·D-31)")
    void uniqueProviderPgTid_violation() {
        disableForeignKeyChecks();
        Payment first = buildPayment(ORDER_ID, attemptKey("TID1"));
        first.complete(NOW, "MOCK_PG", "tid_same");
        paymentRepository.saveAndFlush(first);

        Payment second = buildPayment(200L, attemptKey("TID2"));
        second.complete(NOW, "MOCK_PG", "tid_same");

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_payment_pg_tid_provider: pg_tid 존재·pg_provider NULL → CHECK 위반(D-31)")
    void checkPgTidProvider_violation() {
        disableForeignKeyChecks();
        Payment payment = buildPayment(ORDER_ID, attemptKey("CHK"));
        // 도메인 메서드는 provider 필수라 우회 — CHECK 제약 자체를 검증하기 위해 필드 직접 설정
        ReflectionTestUtils.setField(payment, "pgTid", "tid_orphan");

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

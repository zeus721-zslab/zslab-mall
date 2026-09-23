package com.zslab.mall.settlement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.settlement.service.SettlementCreationService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정산 생성 스냅샷 격리 통합 테스트(Track 104-1 D-215 외부 검토 반영·실 MariaDB). 앱 전역은 READ COMMITTED라, 정산 생성이 한 트랜잭션에서
 * 매출(SALE) 조회와 환불(REFUND) 조회를 따로 하면 그 사이에 커밋된 환불이 환불 쪽에만 섞일 수 있다. 정산 생성 트랜잭션은 REPEATABLE READ로
 * 두 조회가 같은 시점(첫 조회의 스냅샷)을 보는지 확인한다.
 *
 * <p><b>끼워 넣기</b>: 환불 소스 조회({@code RefundRepository.findSettlementRefundSources}) 진입에서 스파이가 멈춘다 — 매출 조회는 이미
 * 끝났다. 그동안 다른 트랜잭션이 같은 기간·같은 셀러의 COMPLETED 환불을 커밋하고 래치를 푼다(ClaimLockRace·DeliveryLockRace 래치 방식).
 * 한 시점 기준이면 끼어든 환불은 이번 정산에 들어가지 않는다(다음 재생성에서 반영).
 *
 * <p><b>시드</b>: 셀러 1 · 기간(마감된 2025-03) 안에 구매확정된 매출 품목 10,000 · 기간 전에 확정된 품목의 반품 클레임(환불은 끼어드는 쪽이 넣는다).
 * FK는 시드·끼워 넣기 트랜잭션에서만 끈다. 클래스에 {@code @Transactional}을 두지 않는다(실제 커밋 관찰).
 */
@TestPropertySource(properties = {
        "zslab.settlement.monthly-creation.enabled=false",
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class SettlementSnapshotIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final long SELLER_ID = 6301L;
    private static final long ORDER_ID = 6301L;
    private static final long SALE_ITEM_ID = 6311L;
    private static final long REFUND_ITEM_ID = 6312L;
    private static final long CLAIM_ID = 6312L;
    private static final long REFUND_ID = 6312L;
    private static final long DUMMY_PAYMENT_ID = 6301L;
    private static final long SALE_AMOUNT = 10_000L;
    private static final long INTERLEAVED_REFUND_AMOUNT = 3_000L;
    private static final int SETTLEMENT_YEAR = 2025;
    private static final int SETTLEMENT_MONTH = 3;
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2025, 3, 1, 0, 0);
    private static final LocalDateTime IN_PERIOD = LocalDateTime.of(2025, 3, 15, 12, 0);
    private static final LocalDateTime BEFORE_PERIOD = LocalDateTime.of(2025, 2, 10, 12, 0);
    private static final AuditContext ADMIN = AuditContext.of(6309L, "ADMIN");
    private static final int RACE_TIMEOUT_SECONDS = 30;

    @MockitoSpyBean
    private RefundRepository refundRepository;

    @Autowired
    private SettlementCreationService settlementCreationService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private final AtomicBoolean holdBeforeRefundQuery = new AtomicBoolean(false);
    private CountDownLatch saleQueried;
    private CountDownLatch refundCommitted;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        saleQueried = new CountDownLatch(1);
        refundCommitted = new CountDownLatch(1);
        // 정지점: 환불 소스 조회 직전(매출 조회는 끝난 뒤). 무장된 1회만 멈춘다. 리포지토리는 인터페이스 프록시라 callRealMethod가 안 되므로
        // 스파이의 기본 응답(실 빈 위임)으로 이어 호출한다.
        Answer<?> delegateToRealRepository = Mockito.mockingDetails(refundRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            if (holdBeforeRefundQuery.compareAndSet(true, false)) {
                saleQueried.countDown();
                awaitQuietly(refundCommitted);
            }
            return delegateToRealRepository.answer(invocation);
        }).when(refundRepository).findSettlementRefundSources(any(), any(), any(), any());
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        refundCommitted.countDown();
        cleanup();
    }

    @Test
    @DisplayName("P1 월 정산 생성 중 매출·환불 조회 사이에 환불 커밋 → 정산은 한 시점 기준(끼어든 환불 미포함·REFUND 품목 0)")
    void monthlyCreation_refundCommittedBetweenQueries_isNotMixedIn() throws Exception {
        runWithInterleavedRefund(() -> settlementCreationService.createMonthlySettlements(SETTLEMENT_YEAR, SETTLEMENT_MONTH, ADMIN));

        assertThat(settlementAmounts()).isEqualTo("gross=" + SALE_AMOUNT + " refund=0");
        assertThat(refundItemCount()).isZero();
    }

    @Test
    @DisplayName("P2 정산 재생성 중 매출·환불 조회 사이에 환불 커밋 → 재생성 결과도 한 시점 기준(끼어든 환불 미포함)")
    void regeneration_refundCommittedBetweenQueries_isNotMixedIn() throws Exception {
        settlementCreationService.createMonthlySettlements(SETTLEMENT_YEAR, SETTLEMENT_MONTH, ADMIN);
        Long settlementId = settlementId();

        runWithInterleavedRefund(() -> settlementCreationService.regenerate(settlementId, "스냅샷 경합", ADMIN));

        assertThat(settlementAmounts()).isEqualTo("gross=" + SALE_AMOUNT + " refund=0");
        assertThat(refundItemCount()).isZero();
    }

    // ---------- 끼워 넣기 ----------

    /** 정산 작업을 환불 조회 직전에 세우고, 그동안 같은 기간 환불을 다른 트랜잭션으로 커밋한 뒤 풀어 준다. */
    private void runWithInterleavedRefund(Runnable settlementWork) throws Exception {
        holdBeforeRefundQuery.set(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> settlement = pool.submit(settlementWork);
            assertThat(saleQueried.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("정산이 환불 조회 직전 정지점에 도달").isTrue();
            commitInterleavedRefund();
            refundCommitted.countDown();
            settlement.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private void commitInterleavedRefund() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, refunded_at, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'ssi_rfn_0001', ?, NOW(6), NOW(6))",
                        REFUND_ID, pid("rfn_", "SSIRFN"), CLAIM_ID, DUMMY_PAYMENT_ID, INTERLEAVED_REFUND_AMOUNT, IN_PERIOD);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("경합 래치 대기 중 인터럽트", exception);
        }
    }

    // ---------- seed·helpers ----------
    // 모든 시드 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '스냅샷셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "SSISLR"));
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                                + "created_at, updated_at) VALUES (?, ?, 1, ?, 'CONFIRMED', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "SSIORD"), "ORDSSI" + ORDER_ID, SALE_AMOUNT * 2);
                seedConfirmedItem(SALE_ITEM_ID, "SSIOIT1", "CONFIRMED", IN_PERIOD);
                seedConfirmedItem(REFUND_ITEM_ID, "SSIOIT2", "RETURNED", BEFORE_PERIOD);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                                + "version, created_at, updated_at) VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', 'COMPLETED', 'DELIVERED', 0, "
                                + "NOW(6), NOW(6))",
                        CLAIM_ID, pid("clm_", "SSICLM"), REFUND_ITEM_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedConfirmedItem(long id, String tag, String itemStatus, LocalDateTime confirmedAt) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, commission_rate, item_status, confirmed_at, created_at, updated_at, product_name) "
                        + "VALUES (?, ?, ?, 1, 1, ?, 1, ?, ?, 1000, ?, ?, NOW(6), NOW(6), '스냅샷 상품')",
                id, pid("oit_", tag), ORDER_ID, SELLER_ID, SALE_AMOUNT, SALE_AMOUNT, itemStatus, confirmedAt);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'SETTLEMENT' AND target_id IN "
                        + "(SELECT id FROM settlement WHERE seller_id = ?)", SELLER_ID);
                jdbc.update("DELETE FROM settlement_item WHERE settlement_id IN (SELECT id FROM settlement WHERE seller_id = ?)",
                        SELLER_ID);
                jdbc.update("DELETE FROM settlement WHERE seller_id = ?", SELLER_ID);
                jdbc.update("DELETE FROM refund WHERE id = ?", REFUND_ID);
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM order_item WHERE order_id = ?", ORDER_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private Long settlementId() {
        return jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ? AND period_start = ?", Long.class,
                SELLER_ID, PERIOD_START);
    }

    private String settlementAmounts() {
        return jdbc.queryForObject("SELECT CONCAT('gross=', gross_amount, ' refund=', refund_amount) FROM settlement "
                + "WHERE seller_id = ? AND period_start = ?", String.class, SELLER_ID, PERIOD_START);
    }

    private long refundItemCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_item WHERE item_type = 'REFUND' AND settlement_id IN "
                + "(SELECT id FROM settlement WHERE seller_id = ?)", Long.class, SELLER_ID);
        return count == null ? 0L : count;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

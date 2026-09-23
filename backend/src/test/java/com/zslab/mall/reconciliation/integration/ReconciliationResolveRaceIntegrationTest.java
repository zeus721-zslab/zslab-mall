package com.zslab.mall.reconciliation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.reconciliation.repository.ReconciliationIssueRepository;
import com.zslab.mall.reconciliation.service.AdminReconciliationIssueService;
import com.zslab.mall.refund.enums.RefundCallbackStatus;
import com.zslab.mall.refund.service.RefundService;
import com.zslab.mall.support.AbstractIntegrationTest;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 해결 × 재전송 매칭 자동 해소 경합(Track 104-2 D-216·외부 검토 지적 3·실 MariaDB·LockRace 방식). 관리자 해결이 불일치 행 락을 쥔 채
 * 커밋 전에 멈춘 사이, 매칭 없던 환불 통지가 재전송으로 처리되며 같은 행을 자동 해소하려 한다. 자동 해소가 관리자 커밋을 덮어쓰지 않고
 * (처리자·메모 유지) 감사도 1건(관리자)만 남는지 본다.
 *
 * <p>정지점: 관리자 해결의 행 락 조회({@code findByIdForUpdate}) 직후 — 무장된 1회만 멈춘다. 리포지토리는 인터페이스 프록시라 callRealMethod가
 * 안 되므로 스파이의 기본 응답(실 빈 위임)으로 이어 호출한다(SettlementSnapshotIsolationIntegrationTest 선례).
 */
class ReconciliationResolveRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 6801L;
    private static final long BUYER_ID = 6802L;
    private static final long ORDER_ID = 6801L;
    private static final long ITEM_ID = 6801L;
    private static final long VARIANT_ID = 6801L;
    private static final long SELLER_ID = 6801L;
    private static final long PRODUCT_ID = 6801L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 6801L;
    private static final long PAYMENT_ID = 6801L;
    private static final long CLAIM_ID = 6801L;
    private static final long REFUND_ID = 6801L;
    private static final long AMOUNT = 10_000L;
    private static final String PG_REFUND_ID = "rrr_rfn_late";
    private static final String UNMATCHED_KEY = "pg-refund:" + PG_REFUND_ID + ":SUCCESS";
    private static final String ADMIN_MEMO = "관리자가 PG 관리 화면에서 확인";
    private static final int RACE_TIMEOUT_SECONDS = 30;
    /** 뒤 작업이 이 시간 안에 끝나지 않으면(isDone false) 앞 트랜잭션의 락에 막힌 것으로 본다. */
    private static final int BLOCK_PROBE_MILLIS = 2_000;

    @MockitoSpyBean
    private ReconciliationIssueRepository reconciliationIssueRepository;
    @Autowired
    private AdminReconciliationIssueService adminReconciliationIssueService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private final AtomicBoolean holdAdminResolve = new AtomicBoolean(false);
    private CountDownLatch adminHolding;
    private CountDownLatch releaseAdmin;
    private long issueId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        adminHolding = new CountDownLatch(1);
        releaseAdmin = new CountDownLatch(1);
        Answer<?> delegateToRealRepository =
                Mockito.mockingDetails(reconciliationIssueRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object locked = delegateToRealRepository.answer(invocation);
            if (holdAdminResolve.compareAndSet(true, false)) {
                adminHolding.countDown();
                awaitQuietly(releaseAdmin);
            }
            return locked;
        }).when(reconciliationIssueRepository).findByIdForUpdate(any());
        cleanup();
        seed();
        issueId = jdbc.queryForObject("SELECT id FROM reconciliation_issue WHERE dedupe_key = ?", Long.class, UNMATCHED_KEY);
    }

    @AfterEach
    void tearDown() {
        releaseAdmin.countDown();
        try {
            cleanup();
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    @DisplayName("관리자 해결(행 락·커밋 전 정지) × 재전송 매칭 자동 해소 → 자동 해소는 대기 후 아무것도 바꾸지 않음 · 관리자 처리자·메모 유지 · 감사 1건")
    void adminResolve_vsAutoResolve_keepsAdminResolution() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            holdAdminResolve.set(true);
            Future<?> admin = executor.submit(() ->
                    adminReconciliationIssueService.resolve(issueId, ADMIN_MEMO, AuditContext.of(ADMIN_ID, "ADMIN")));
            assertThat(adminHolding.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> retry = executor.submit(() -> refundService.handleCallback(PG_REFUND_ID, RefundCallbackStatus.SUCCESS, null));
            TimeUnit.MILLISECONDS.sleep(BLOCK_PROBE_MILLIS);
            boolean retryBlocked = !retry.isDone();

            releaseAdmin.countDown();
            admin.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            retry.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(jdbc.queryForObject("SELECT CONCAT(status, '|', COALESCE(resolved_by, 'NULL'), '|', resolution_memo) "
                    + "FROM reconciliation_issue WHERE id = ?", String.class, issueId))
                    .isEqualTo("RESOLVED|" + ADMIN_ID + "|" + ADMIN_MEMO);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'RECONCILIATION_ISSUE' AND target_id = ?",
                    Integer.class, issueId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, REFUND_ID)).isEqualTo("COMPLETED");
            assertThat(retryBlocked).as("자동 해소가 관리자 행 락에서 대기").isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------- 시드·정리(모든 변수 ? 바인딩) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, 'usr_rrr_6801', '경합 관리자', NOW(6), NOW(6))",
                    ADMIN_ID);
            // 동시 스레드의 서비스 트랜잭션은 FK 검사가 켜진 커넥션이라 품목·재고 UPDATE가 참조하는 상위 행을 실제로 둔다
            // (PaymentCallbackConcurrencyIntegrationTest 패턴·카테고리·옵션 값만 FK_CHECKS=0 더미).
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, 'usr_rrr_6802', NOW(6), NOW(6))", BUYER_ID);
            jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                    + "VALUES (?, 'slr_rrr_6801', '경합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID);
            jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                    + "VALUES (?, 'prd_rrr_6801', ?, ?, '경합상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, SELLER_ID, DUMMY_FK_ID);
            jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, is_soldout_manual, "
                    + "display_order, option1_value_id, created_at, updated_at) VALUES (?, 'var_rrr_6801', ?, 'VCRRR', 0, 'SALE', 0, 1, ?, "
                    + "NOW(6), NOW(6))", VARIANT_ID, PRODUCT_ID, DUMMY_FK_ID);
            jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, ordered_at, "
                    + "created_at, updated_at) VALUES (?, 'ord_rrr_6801', ?, 'ORDRRR-6801', 'PAID', ?, 0, 0, NOW(6), NOW(6), NOW(6))",
                    ORDER_ID, BUYER_ID, AMOUNT);
            jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                    + "item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, 'oit_rrr_6801', ?, ?, ?, ?, 1, ?, ?, "
                    + "'CANCEL_REQUESTED', NOW(6), NOW(6), '경합 상품', 1000)", ITEM_ID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, AMOUNT, AMOUNT);
            jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, pg_provider, pg_tid, paid_at, "
                    + "created_at, updated_at) VALUES (?, 'pay_rrr_6801', ?, 'CARD', ?, 'PAID', 'pat_rrr_6801', 'MOCK_PG', 'tid_rrr_6801', "
                    + "NOW(6), NOW(6), NOW(6))", PAYMENT_ID, ORDER_ID, AMOUNT);
            jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, requested_at, "
                    + "previous_order_item_status, version, created_at, updated_at) VALUES (?, 'clm_rrr_6801', ?, 'CANCEL', 'BUYER_CHANGED_MIND', "
                    + "'APPROVED', ?, NOW(6), 'PAID', 0, NOW(6), NOW(6))", CLAIM_ID, ITEM_ID, BUYER_ID);
            jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, created_at, updated_at) "
                    + "VALUES (?, 'rfn_rrr_6801', ?, ?, ?, 'PENDING', ?, NOW(6), NOW(6))", REFUND_ID, CLAIM_ID, PAYMENT_ID, AMOUNT, PG_REFUND_ID);
            jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                    + "VALUES (?, ?, 10, 0, 10, NOW(6), NOW(6))", VARIANT_ID, VARIANT_ID);
            // 환불 개시 커밋 전에 도착해 매칭 없음으로 기록된 SUCCESS 통지(이후 환불 행이 커밋돼 재전송은 매칭된다)
            jdbc.update("INSERT INTO reconciliation_issue (issue_type, dedupe_key, pg_refund_id, detail, status, detected_at, created_at) "
                    + "VALUES ('PG_UNMATCHED_CALLBACK', ?, ?, '{\"reason\":\"NO_MATCHING_REFUND\"}', 'OPEN', NOW(6), NOW(6))",
                    UNMATCHED_KEY, PG_REFUND_ID);
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'RECONCILIATION_ISSUE' AND target_id IN "
                    + "(SELECT id FROM reconciliation_issue WHERE dedupe_key = ? OR order_id = ?)", UNMATCHED_KEY, ORDER_ID);
            jdbc.update("DELETE FROM reconciliation_issue WHERE dedupe_key = ? OR order_id = ?", UNMATCHED_KEY, ORDER_ID);
            jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER_ID);
            jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", VARIANT_ID);
            jdbc.update("DELETE FROM inventory WHERE id = ?", VARIANT_ID);
            jdbc.update("DELETE FROM refund WHERE id = ?", REFUND_ID);
            jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
            jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
            jdbc.update("DELETE FROM order_item WHERE id = ?", ITEM_ID);
            jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
            jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
            jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
            jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
            jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", ADMIN_ID, BUYER_ID);
        });
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.zslab.mall.refund.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.exception.RefundInvariantViolationException;
import com.zslab.mall.refund.service.RefundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 환불 webhook end-to-end 통합 테스트(실 MariaDB·Flyway V1~V4). initiate(서비스 직접 호출·REST 미노출) → webhook 콜백 →
 * markCompleted/markFailed → RefundCompleted(AFTER_COMMIT) → Claim·Payment 갱신까지 실제 커밋 경로로 검증한다(라이브 트랩 차단).
 *
 * <p><b>트랜잭션</b>: AFTER_COMMIT 핸들러는 실제 커밋 후에만 실행되므로 클래스에 {@code @Transactional}을 두지 않는다.
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}으로 상위 그래프(order·user 등) 없이 커밋한다
 * (CheckoutIntegrationTest FK 비활성 패턴 준용). 검증은 {@link JdbcTemplate} 직접 조회(1차 캐시 무관)로 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundWebhookIntegrationTest {

    private static final long ORDER_ID = 9001L;
    private static final long ORDER_ITEM_ID = 9001L;
    private static final long PAYMENT_ID = 9001L;
    private static final long CLAIM_ID = 9001L;
    private static final long FULL_AMOUNT = 10_000L;

    static final MariaDBContainer<?> MARIADB;

    static {
        MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RefundService refundService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("webhook SUCCESS e2e: initiate → 콜백 → Refund COMPLETED·Claim COMPLETED·Payment CANCELLED")
    void webhook_success_endToEnd() throws Exception {
        seed(ClaimType.CANCEL, FULL_AMOUNT);
        String pgRefundId = refundService.initiate(CLAIM_ID, FULL_AMOUNT).getPgRefundId();

        postWebhook(pgRefundId, "SUCCESS");

        assertThat(refundStatus(pgRefundId)).isEqualTo("COMPLETED");
        assertThat(claimStatus()).isEqualTo("COMPLETED");
        assertThat(paymentStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("webhook FAIL e2e: initiate → 콜백 FAIL → Refund FAILED·Claim/Payment 미전이")
    void webhook_fail_endToEnd() throws Exception {
        seed(ClaimType.CANCEL, FULL_AMOUNT);
        String pgRefundId = refundService.initiate(CLAIM_ID, FULL_AMOUNT).getPgRefundId();

        postWebhook(pgRefundId, "FAIL");

        assertThat(refundStatus(pgRefundId)).isEqualTo("FAILED");
        assertThat(claimStatus()).isEqualTo("APPROVED");
        assertThat(paymentStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("webhook 중복 콜백 RFN-3: 2회 SUCCESS → 200 no-op·COMPLETED 1건·Payment 1회 취소")
    void webhook_duplicateCallback_idempotent() throws Exception {
        seed(ClaimType.CANCEL, FULL_AMOUNT);
        String pgRefundId = refundService.initiate(CLAIM_ID, FULL_AMOUNT).getPgRefundId();

        postWebhook(pgRefundId, "SUCCESS");
        postWebhook(pgRefundId, "SUCCESS"); // 멱등 재수신 → 200 no-op

        assertThat(refundStatus(pgRefundId)).isEqualTo("COMPLETED");
        assertThat(refundCount()).isEqualTo(1);
        assertThat(paymentStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("재시도 RFN-2: FAILED 후 재 initiate → 새 Refund 행 생성(총 2건)")
    void retry_afterFailed_createsNewRow() throws Exception {
        seed(ClaimType.CANCEL, FULL_AMOUNT);
        String firstPgRefundId = refundService.initiate(CLAIM_ID, FULL_AMOUNT).getPgRefundId();
        postWebhook(firstPgRefundId, "FAIL");

        Refund retry = refundService.initiate(CLAIM_ID, FULL_AMOUNT);

        assertThat(refundStatus(firstPgRefundId)).isEqualTo("FAILED");
        assertThat(refundStatus(retry.getPgRefundId())).isEqualTo("PENDING");
        assertThat(refundCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("PAY-1 초과 차단: 결제액 초과 환불 initiate → RefundInvariantViolationException·행 미생성")
    void initiate_payOneExceeded_blocked() {
        seed(ClaimType.CANCEL, FULL_AMOUNT);

        assertThatThrownBy(() -> refundService.initiate(CLAIM_ID, FULL_AMOUNT + 5_000L))
                .isInstanceOf(RefundInvariantViolationException.class);
        assertThat(refundCount()).isZero();
    }

    @Test
    @DisplayName("Claim type RETURN: Refund COMPLETED·Payment CANCELLED 되어도 Claim 미전이(본 트랙 CANCEL만)")
    void returnClaim_doesNotCompleteClaim() throws Exception {
        seed(ClaimType.RETURN, FULL_AMOUNT);
        String pgRefundId = refundService.initiate(CLAIM_ID, FULL_AMOUNT).getPgRefundId();

        postWebhook(pgRefundId, "SUCCESS");

        assertThat(refundStatus(pgRefundId)).isEqualTo("COMPLETED");
        assertThat(claimStatus()).isEqualTo("APPROVED"); // RETURN은 본 트랙 미전이
        assertThat(paymentStatus()).isEqualTo("CANCELLED"); // Payment 핸들러는 type 무관·Σ==amount 시 취소
    }

    // ---------- helpers ----------

    private void postWebhook(String pgRefundId, String status) throws Exception {
        String body = "{ \"pgRefundId\": \"" + pgRefundId + "\", \"status\": \"" + status + "\" }";
        mockMvc.perform(post("/api/webhooks/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** order_item·payment(PAID)·claim(APPROVED)을 고정 id로 시드한다(FK 비활성·상위 그래프 생략). */
    private void seed(ClaimType type, long paymentAmount) {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("INSERT INTO order_item "
                    + "(id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                    + "item_status, created_at, updated_at) "
                    + "VALUES (?, 'oit_track5_it_0001', ?, 1, 1, 1, 1, ?, ?, 'PAID', NOW(6), NOW(6))",
                    ORDER_ITEM_ID, ORDER_ID, paymentAmount, paymentAmount);
            jdbc.update("INSERT INTO payment "
                    + "(id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, "
                    + "paid_at, created_at, updated_at) "
                    + "VALUES (?, 'pay_track5_it_0001', ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_track5_it_0001', "
                    + "'pat_track5_it_0001', NOW(6), NOW(6), NOW(6))",
                    PAYMENT_ID, ORDER_ID, paymentAmount);
            jdbc.update("INSERT INTO claim "
                    + "(id, public_id, order_item_id, type, reason_code, status, created_at, updated_at) "
                    + "VALUES (?, 'clm_track5_it_0001', ?, ?, 'CHANGE_MIND', 'APPROVED', NOW(6), NOW(6))",
                    CLAIM_ID, ORDER_ITEM_ID, type.name());
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM refund WHERE claim_id = ?", CLAIM_ID);
            jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
            jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
            jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
        });
    }

    private String refundStatus(String pgRefundId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE pg_refund_id = ?", String.class, pgRefundId);
    }

    private int refundCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, CLAIM_ID);
    }

    private String claimStatus() {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, CLAIM_ID);
    }

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }
}

package com.zslab.mall.reconciliation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PG 통지 충돌 기록 통합 테스트(Track 104-2 D-216·invariants P1·P6·실 MariaDB). PG에서 일어난 결제 성공·취소·환불 완료가 내부 규칙과
 * 충돌하면 거부·롤백(사실 소실) 대신 불일치 1행을 남기고 웹훅은 200으로 정상 종료하는지, 내부 상태(결제·환불·주문·품목·재고)는 그대로인지 본다.
 * mock 결제 콜백(구매자 브라우저)은 기록은 남기되 응답은 기존 4xx를 유지한다(D-216 §1-A). 환불 완료 시점 검사(U7·U8)도 함께 본다.
 *
 * <p>클래스에 {@code @Transactional}을 두지 않는다(실제 커밋 관찰). 시드는 FK 비활성으로 상위 그래프를 생략하고 테스트 전체에 유지한다
 * (웹훅 처리 중 order_item 전 컬럼 UPDATE가 없는 seller를 참조 — PaymentWebhookIntegrationTest와 같은 LT-02 규약).
 */
@AutoConfigureMockMvc
class PgCallbackReconciliationIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 6401L;
    private static final long AMOUNT = 10_000L;

    // 결제 시나리오: 주문 PENDING_PAYMENT · 품목 ORDERED · 결제 PENDING · 재고 예약 1
    private static final long PAY_ORDER_ID = 6401L;
    private static final long PAY_ITEM_ID = 6401L;
    private static final long PAYMENT_ID = 6401L;
    private static final long SECOND_PAYMENT_ID = 6402L;   // 같은 주문의 다른 결제(PAY-3a)
    private static final long OTHER_ORDER_PAYMENT_ID = 6403L; // 다른 주문의 결제(pgTid 충돌)
    private static final long PAY_VARIANT_ID = 6401L;
    private static final String ATTEMPT_KEY = "pat_rci_0001";

    // 환불 시나리오: 주문 PAID · 결제 PAID · 취소 클레임 APPROVED · 환불 PENDING
    private static final long RF_ORDER_ID = 6451L;
    private static final long RF_ITEM_ID = 6451L;
    private static final long RF_SIBLING_ITEM_ID = 6452L;
    private static final long RF_PAYMENT_ID = 6451L;
    private static final long RF_CLAIM_ID = 6451L;
    private static final long RF_SIBLING_CLAIM_ID = 6452L;
    private static final long REFUND_ID = 6451L;
    private static final long SIBLING_REFUND_ID = 6452L;
    private static final long RF_VARIANT_ID = 6451L;
    private static final String PG_REFUND_ID = "rci_rfn_0001";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private AuthHeaders authHeaders;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedPaymentScenario();
        seedRefundScenario();
    }

    @AfterEach
    void tearDown() {
        try {
            cleanup();
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    // ---------- 결제 통지 ----------

    @Test
    @DisplayName("P-1 같은 주문에 PAID 결제가 있는데 다른 결제 SUCCESS → 200·불일치 1행·결제 PENDING·주문·재고 불변")
    void success_secondPaidPaymentOnSameOrder_recordsInsteadOfRejecting() throws Exception {
        sql("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, pg_provider, pg_tid, paid_at, "
                + "created_at, updated_at) VALUES (?, 'pay_rci_0002', ?, 'CARD', ?, 'PAID', 'pat_rci_0002', 'MOCK_PG', 'tid_rci_paid', "
                + "NOW(6), NOW(6), NOW(6))", SECOND_PAYMENT_ID, PAY_ORDER_ID, AMOUNT);

        paymentWebhook("SUCCESS", ATTEMPT_KEY, "tid_rci_0001").andExpect(status().isOk());

        assertThat(issueCount("PG_PAYMENT_SUCCESS_CONFLICT", "payment:" + PAYMENT_ID)).isEqualTo(1);
        assertThat(paymentStatus(PAYMENT_ID) + "/" + orderStatus(PAY_ORDER_ID)).isEqualTo("PENDING/PENDING_PAYMENT");
        assertThat(reserved(PAY_VARIANT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("P-2 종료 주문(PAYMENT_EXPIRED)의 늦은 SUCCESS → 200·불일치 1행(주문 id 포함)·결제 PENDING·재고 불변")
    void success_lateApprovalOnExpiredOrder_recordsInsteadOfRejecting() throws Exception {
        sql("UPDATE `order` SET status = 'PAYMENT_EXPIRED' WHERE id = ?", PAY_ORDER_ID);

        paymentWebhook("SUCCESS", ATTEMPT_KEY, "tid_rci_0002").andExpect(status().isOk());

        assertThat(issueCount("PG_PAYMENT_SUCCESS_CONFLICT", "payment:" + PAYMENT_ID)).isEqualTo(1);
        assertThat(issueOrderId("PG_PAYMENT_SUCCESS_CONFLICT", "payment:" + PAYMENT_ID)).isEqualTo(PAY_ORDER_ID);
        assertThat(paymentStatus(PAYMENT_ID) + "/" + orderStatus(PAY_ORDER_ID)).isEqualTo("PENDING/PAYMENT_EXPIRED");
        assertThat(reserved(PAY_VARIANT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("P-3 만료된 결제(EXPIRED)의 SUCCESS → 200·불일치 1행·결제 EXPIRED 유지")
    void success_onExpiredPayment_recordsInsteadOfRejecting() throws Exception {
        sql("UPDATE payment SET status = 'EXPIRED' WHERE id = ?", PAYMENT_ID);

        paymentWebhook("SUCCESS", ATTEMPT_KEY, "tid_rci_0003").andExpect(status().isOk());

        assertThat(issueCount("PG_PAYMENT_SUCCESS_CONFLICT", "payment:" + PAYMENT_ID)).isEqualTo(1);
        assertThat(paymentStatus(PAYMENT_ID)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("P-6 다른 결제에 기록된 pgTid의 SUCCESS → 200·PG_TID_CONFLICT 1행·결제 PENDING·재고 불변")
    void success_pgTidAlreadyUsed_recordsInsteadOfRejecting() throws Exception {
        sql("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, pg_provider, pg_tid, paid_at, "
                + "created_at, updated_at) VALUES (?, 'pay_rci_0003', 6499, 'CARD', ?, 'PAID', 'pat_rci_0003', 'MOCK_PG', 'tid_rci_dup', "
                + "NOW(6), NOW(6), NOW(6))", OTHER_ORDER_PAYMENT_ID, AMOUNT);

        paymentWebhook("SUCCESS", ATTEMPT_KEY, "tid_rci_dup").andExpect(status().isOk());

        assertThat(issueCount("PG_TID_CONFLICT", "payment:" + PAYMENT_ID)).isEqualTo(1);
        assertThat(paymentStatus(PAYMENT_ID) + "/" + orderStatus(PAY_ORDER_ID)).isEqualTo("PENDING/PENDING_PAYMENT");
        assertThat(reserved(PAY_VARIANT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("P-7 매칭 결제 없는 attemptKey의 SUCCESS → 기존 422 유지(PG 재전송 유도·결정 1)·PG_UNMATCHED_CALLBACK 1행(주문 id 없음)은 커밋")
    void success_unmatchedAttemptKey_recordsWithoutOrder() throws Exception {
        paymentWebhook("SUCCESS", "pat_rci_unknown", "tid_rci_0007").andExpect(status().isUnprocessableEntity());

        assertThat(issueCount("PG_UNMATCHED_CALLBACK", "payment-attempt:pat_rci_unknown:SUCCESS")).isEqualTo(1);
        assertThat(issueOrderId("PG_UNMATCHED_CALLBACK", "payment-attempt:pat_rci_unknown:SUCCESS")).isNull();
    }

    @Test
    @DisplayName("P-8 결제 불가 품목(CANCELLED)이 있는 주문의 SUCCESS → 200·불일치 1행·결제 PENDING·품목 불변(도달 경로 미발견 분기)")
    void success_itemNotPayable_recordsInsteadOfFailing() throws Exception {
        sql("UPDATE order_item SET item_status = 'CANCELLED' WHERE id = ?", PAY_ITEM_ID);

        paymentWebhook("SUCCESS", ATTEMPT_KEY, "tid_rci_0008").andExpect(status().isOk());

        assertThat(issueCount("PG_PAYMENT_SUCCESS_CONFLICT", "payment:" + PAYMENT_ID)).isEqualTo(1);
        assertThat(paymentStatus(PAYMENT_ID) + "/" + itemStatus(PAY_ITEM_ID)).isEqualTo("PENDING/CANCELLED");
    }

    @Test
    @DisplayName("P-9 PAID 결제의 CANCEL 통지 → 200·PG_PAYMENT_CANCEL_ON_PAID 1행·결제 PAID·환불 행 없음")
    void cancel_onPaidPayment_recordsInsteadOfRejecting() throws Exception {
        sql("UPDATE payment SET status = 'PAID', pg_provider = 'MOCK_PG', pg_tid = 'tid_rci_paid9', paid_at = NOW(6) WHERE id = ?",
                PAYMENT_ID);
        sql("UPDATE `order` SET status = 'PAID' WHERE id = ?", PAY_ORDER_ID);

        paymentWebhook("CANCEL", ATTEMPT_KEY, null).andExpect(status().isOk());

        assertThat(issueCount("PG_PAYMENT_CANCEL_ON_PAID", "payment:" + PAYMENT_ID)).isEqualTo(1);
        assertThat(paymentStatus(PAYMENT_ID)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE payment_id = ?", Integer.class, PAYMENT_ID)).isZero();
    }

    @Test
    @DisplayName("재전송 멱등: 같은 충돌 통지 2회 → 두 번 다 200·불일치 1행")
    void sameConflictResent_keepsOneIssue() throws Exception {
        sql("UPDATE `order` SET status = 'PAYMENT_EXPIRED' WHERE id = ?", PAY_ORDER_ID);

        paymentWebhook("SUCCESS", ATTEMPT_KEY, "tid_rci_0010").andExpect(status().isOk());
        paymentWebhook("SUCCESS", ATTEMPT_KEY, "tid_rci_0010").andExpect(status().isOk());

        assertThat(issueCount("PG_PAYMENT_SUCCESS_CONFLICT", "payment:" + PAYMENT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("mock 결제 콜백(구매자) 늦은 승인 → 응답은 기존 422 INVALID_CALLBACK 유지·불일치 1행은 남음·결제 PENDING")
    void mockCallback_conflict_keeps422ButRecords() throws Exception {
        sql("UPDATE `order` SET status = 'PAYMENT_EXPIRED' WHERE id = ?", PAY_ORDER_ID);

        mockMvc.perform(post("/api/v1/payments/mock-callback")
                        .headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptKey\": \"" + ATTEMPT_KEY + "\", \"callbackType\": \"SUCCESS\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));

        assertThat(issueCount("PG_PAYMENT_SUCCESS_CONFLICT", "payment:" + PAYMENT_ID)).isEqualTo(1);
        assertThat(paymentStatus(PAYMENT_ID)).isEqualTo("PENDING");
    }

    // ---------- 환불 통지 ----------

    @Test
    @DisplayName("R-1 환불 완료가 결제액 초과(다른 완료 환불 8,000 + 이번 5,000 > 10,000) → 200·불일치 1행·환불 PENDING·클레임 APPROVED")
    void refundSuccess_exceedsPayment_recordsInsteadOfRejecting() throws Exception {
        seedSiblingCompletedRefund(8_000L);
        sql("UPDATE refund SET amount = 5000 WHERE id = ?", REFUND_ID);

        refundWebhook("SUCCESS", PG_REFUND_ID).andExpect(status().isOk());

        assertThat(issueCount("PG_REFUND_EXCEEDS_PAYMENT", "refund:" + REFUND_ID)).isEqualTo(1);
        assertThat(refundStatus(REFUND_ID) + "/" + claimStatus(RF_CLAIM_ID)).isEqualTo("PENDING/APPROVED");
    }

    @Test
    @DisplayName("R-2 실패 처리된 환불(FAILED)의 SUCCESS → 200·불일치 1행·환불 FAILED 유지")
    void refundSuccess_onFailedRefund_recordsInsteadOfFailing() throws Exception {
        sql("UPDATE refund SET status = 'FAILED' WHERE id = ?", REFUND_ID);

        refundWebhook("SUCCESS", PG_REFUND_ID).andExpect(status().isOk());

        assertThat(issueCount("PG_REFUND_SUCCESS_ON_FAILED", "refund:" + REFUND_ID)).isEqualTo(1);
        assertThat(refundStatus(REFUND_ID)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("R-3 매칭 환불 없는 pgRefundId의 SUCCESS → 기존 404 유지(PG 재전송 유도·결정 1)·PG_UNMATCHED_CALLBACK 1행(주문 id 없음)은 커밋")
    void refundSuccess_unmatched_recordsWithoutOrder() throws Exception {
        refundWebhook("SUCCESS", "rci_rfn_unknown").andExpect(status().isNotFound());

        assertThat(issueCount("PG_UNMATCHED_CALLBACK", "pg-refund:rci_rfn_unknown:SUCCESS")).isEqualTo(1);
        assertThat(issueOrderId("PG_UNMATCHED_CALLBACK", "pg-refund:rci_rfn_unknown:SUCCESS")).isNull();
    }

    @Test
    @DisplayName("결정 1: 환불 개시 커밋 전 도착(매칭 없음 404·기록) → 커밋 뒤 재전송 매칭 → 200·환불 COMPLETED·같은 키 행 SYSTEM 자동 해소(메모)")
    void refundSuccess_unmatchedThenRetriedAfterCommit_autoResolves() throws Exception {
        String latePrefix = "pg-refund:rci_rfn_late";
        refundWebhook("SUCCESS", "rci_rfn_late").andExpect(status().isNotFound());
        assertThat(unmatchedIssueState(latePrefix)).startsWith("OPEN|");

        // 환불 개시 트랜잭션이 이제 커밋됐다 — 같은 pgRefundId가 환불 행에 보인다
        sql("UPDATE refund SET pg_refund_id = 'rci_rfn_late' WHERE id = ?", REFUND_ID);
        refundWebhook("SUCCESS", "rci_rfn_late").andExpect(status().isOk());

        assertThat(refundStatus(REFUND_ID)).isEqualTo("COMPLETED");
        assertThat(unmatchedIssueState(latePrefix)).isEqualTo("RESOLVED|NULL|재전송 매칭으로 자동 해소");
        assertThat(systemResolveAuditCount(latePrefix)).isEqualTo(1);
    }

    @Test
    @DisplayName("외부 검토 지적 2: 결제 SUCCESS 매칭 없음 기록 → 커밋 뒤 CANCEL 통지가 매칭돼 처리 → SUCCESS 불일치는 OPEN 유지(다른 종류 통지는 해소하지 않음)")
    void paymentSuccessUnmatched_thenCancelMatched_keepsSuccessIssueOpen() throws Exception {
        String latePrefix = "payment-attempt:pat_rci_late";
        paymentWebhook("SUCCESS", "pat_rci_late", "tid_rci_late").andExpect(status().isUnprocessableEntity());
        assertThat(unmatchedIssueState(latePrefix)).startsWith("OPEN|");

        sql("UPDATE payment SET payment_attempt_key = 'pat_rci_late' WHERE id = ?", PAYMENT_ID);
        paymentWebhook("CANCEL", "pat_rci_late", null).andExpect(status().isOk());

        assertThat(paymentStatus(PAYMENT_ID)).isEqualTo("EXPIRED");
        assertThat(unmatchedIssueState(latePrefix)).startsWith("OPEN|");
        assertThat(systemResolveAuditCount(latePrefix)).isZero();
    }

    @Test
    @DisplayName("외부 검토 지적 2: 결제 SUCCESS 매칭 없음 기록 → 커밋 뒤 같은 SUCCESS 재전송이 매칭돼 PAID 처리 → RESOLVED(시스템·메모)")
    void paymentSuccessUnmatched_thenSuccessRetried_autoResolves() throws Exception {
        String latePrefix = "payment-attempt:pat_rci_late";
        paymentWebhook("SUCCESS", "pat_rci_late", "tid_rci_late").andExpect(status().isUnprocessableEntity());

        sql("UPDATE payment SET payment_attempt_key = 'pat_rci_late' WHERE id = ?", PAYMENT_ID);
        paymentWebhook("SUCCESS", "pat_rci_late", "tid_rci_late").andExpect(status().isOk());

        assertThat(paymentStatus(PAYMENT_ID)).isEqualTo("PAID");
        assertThat(unmatchedIssueState(latePrefix)).isEqualTo("RESOLVED|NULL|재전송 매칭으로 자동 해소");
        assertThat(systemResolveAuditCount(latePrefix)).isEqualTo(1);
    }

    @Test
    @DisplayName("외부 검토 지적 2: 환불 SUCCESS 매칭 없음 기록 → 커밋 뒤 같은 SUCCESS 재전송이 충돌(PAY-1 초과)로 기록만 됨 → 매칭 없음 행은 OPEN 유지(정상 처리 아님)")
    void refundSuccessUnmatched_thenRetriedButConflicted_keepsIssueOpen() throws Exception {
        String latePrefix = "pg-refund:rci_rfn_late";
        refundWebhook("SUCCESS", "rci_rfn_late").andExpect(status().isNotFound());

        seedSiblingCompletedRefund(8_000L);
        sql("UPDATE refund SET amount = 5000, pg_refund_id = 'rci_rfn_late' WHERE id = ?", REFUND_ID);
        refundWebhook("SUCCESS", "rci_rfn_late").andExpect(status().isOk());

        assertThat(refundStatus(REFUND_ID)).isEqualTo("PENDING");
        assertThat(issueCount("PG_REFUND_EXCEEDS_PAYMENT", "refund:" + REFUND_ID)).isEqualTo(1);
        assertThat(unmatchedIssueState(latePrefix)).startsWith("OPEN|");
    }

    @Test
    @DisplayName("R-6 전액 환불인데 결제가 취소 불가 상태(FAILED) → 200·불일치 1행·환불 PENDING·결제 불변(도달 경로 미발견 분기)")
    void refundSuccess_paymentNotCancellable_recordsInsteadOfRejecting() throws Exception {
        sql("UPDATE payment SET status = 'FAILED' WHERE id = ?", RF_PAYMENT_ID);

        refundWebhook("SUCCESS", PG_REFUND_ID).andExpect(status().isOk());

        assertThat(issueCount("FULL_REFUND_PAYMENT_NOT_CANCELLED", "refund:" + REFUND_ID)).isEqualTo(1);
        assertThat(refundStatus(REFUND_ID) + "/" + paymentStatus(RF_PAYMENT_ID)).isEqualTo("PENDING/FAILED");
    }

    @Test
    @DisplayName("U7 구매확정 품목이 있는 주문의 전액 환불 완료 → 기존대로 환불 COMPLETED·결제 CANCELLED + 불일치 1행")
    void refundCompleted_fullWithConfirmedItem_recordsIssue() throws Exception {
        sql("UPDATE order_item SET item_status = 'CONFIRMED', confirmed_at = NOW(6) WHERE id = ?", RF_SIBLING_ITEM_ID);

        refundWebhook("SUCCESS", PG_REFUND_ID).andExpect(status().isOk());

        assertThat(refundStatus(REFUND_ID) + "/" + paymentStatus(RF_PAYMENT_ID)).isEqualTo("COMPLETED/CANCELLED");
        assertThat(issueCount("FULL_REFUND_WITH_CONFIRMED_ITEM", "payment:" + RF_PAYMENT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("U8 교환 클레임에 걸린 환불 완료 → 기존대로 환불 COMPLETED·클레임 APPROVED 유지 + REFUND_ON_INVALID_CLAIM 1행")
    void refundCompleted_onExchangeClaim_recordsIssue() throws Exception {
        sql("UPDATE claim SET type = 'EXCHANGE' WHERE id = ?", RF_CLAIM_ID);
        sql("UPDATE order_item SET item_status = 'EXCHANGE_REQUESTED' WHERE id = ?", RF_ITEM_ID);
        sql("UPDATE refund SET amount = 3000 WHERE id = ?", REFUND_ID);

        refundWebhook("SUCCESS", PG_REFUND_ID).andExpect(status().isOk());

        assertThat(refundStatus(REFUND_ID) + "/" + claimStatus(RF_CLAIM_ID)).isEqualTo("COMPLETED/APPROVED");
        assertThat(issueCount("REFUND_ON_INVALID_CLAIM", "refund:" + REFUND_ID)).isEqualTo(1);
    }

    // ---------- 요청·시드·조회 ----------
    // 모든 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private ResultActions paymentWebhook(String callbackType, String attemptKey, String pgTid) throws Exception {
        String pgTidField = pgTid == null ? "" : "\"pgTid\": \"" + pgTid + "\",";
        return mockMvc.perform(post("/api/webhooks/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\": \"MOCK_PG\", \"callbackType\": \"" + callbackType + "\", "
                        + "\"paymentAttemptKey\": \"" + attemptKey + "\", " + pgTidField
                        + "\"occurredAt\": \"2026-09-23T10:00:00\"}"));
    }

    private ResultActions refundWebhook(String status, String pgRefundId) throws Exception {
        return mockMvc.perform(post("/api/webhooks/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pgRefundId\": \"" + pgRefundId + "\", \"status\": \"" + status + "\"}"));
    }

    private void sql(String statement, Object... args) {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update(statement, args);
        });
    }

    private void seedPaymentScenario() {
        sql("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, 'usr_rci_000000000000001', NOW(6), NOW(6))", BUYER_ID);
        sql("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, ordered_at, "
                + "created_at, updated_at) VALUES (?, 'ord_rci_0001', ?, 'ORDRCI-6401', 'PENDING_PAYMENT', ?, 0, 0, NOW(6), NOW(6), NOW(6))",
                PAY_ORDER_ID, BUYER_ID, AMOUNT);
        sql("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, 'oit_rci_0001', ?, 1, ?, 1, 1, ?, ?, "
                + "'ORDERED', NOW(6), NOW(6), '불일치 상품', 1000)", PAY_ITEM_ID, PAY_ORDER_ID, PAY_VARIANT_ID, AMOUNT, AMOUNT);
        sql("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, created_at, updated_at) "
                + "VALUES (?, 'pay_rci_0001', ?, 'CARD', ?, 'PENDING', ?, NOW(6), NOW(6))", PAYMENT_ID, PAY_ORDER_ID, AMOUNT, ATTEMPT_KEY);
        sql("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, 10, 1, 9, NOW(6), NOW(6))", PAY_VARIANT_ID, PAY_VARIANT_ID);
    }

    private void seedRefundScenario() {
        sql("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, ordered_at, "
                + "created_at, updated_at) VALUES (?, 'ord_rci_0051', ?, 'ORDRCI-6451', 'PAID', ?, 0, 0, NOW(6), NOW(6), NOW(6))",
                RF_ORDER_ID, BUYER_ID, AMOUNT);
        sql("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, 'oit_rci_0051', ?, 1, ?, 1, 1, ?, ?, "
                + "'CANCEL_REQUESTED', NOW(6), NOW(6), '불일치 상품', 1000)", RF_ITEM_ID, RF_ORDER_ID, RF_VARIANT_ID, AMOUNT, AMOUNT);
        sql("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, 'oit_rci_0052', ?, 1, ?, 1, 1, 0, 0, "
                + "'DELIVERED', NOW(6), NOW(6), '불일치 형제 상품', 1000)", RF_SIBLING_ITEM_ID, RF_ORDER_ID, RF_VARIANT_ID);
        sql("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, pg_provider, pg_tid, paid_at, "
                + "created_at, updated_at) VALUES (?, 'pay_rci_0051', ?, 'CARD', ?, 'PAID', 'pat_rci_0051', 'MOCK_PG', 'tid_rci_0051', "
                + "NOW(6), NOW(6), NOW(6))", RF_PAYMENT_ID, RF_ORDER_ID, AMOUNT);
        sql("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, requested_at, "
                + "previous_order_item_status, version, created_at, updated_at) VALUES (?, 'clm_rci_0051', ?, 'CANCEL', 'BUYER_CHANGED_MIND', "
                + "'APPROVED', ?, NOW(6), 'PAID', 0, NOW(6), NOW(6))", RF_CLAIM_ID, RF_ITEM_ID, BUYER_ID);
        sql("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, created_at, updated_at) "
                + "VALUES (?, 'rfn_rci_0051', ?, ?, ?, 'PENDING', ?, NOW(6), NOW(6))", REFUND_ID, RF_CLAIM_ID, RF_PAYMENT_ID, AMOUNT,
                PG_REFUND_ID);
        sql("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, 10, 0, 10, NOW(6), NOW(6))", RF_VARIANT_ID, RF_VARIANT_ID);
    }

    private void seedSiblingCompletedRefund(long amount) {
        sql("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, requested_at, "
                + "previous_order_item_status, version, created_at, updated_at) VALUES (?, 'clm_rci_0052', ?, 'RETURN', 'PRODUCT_DEFECT', "
                + "'COMPLETED', ?, NOW(6), 'DELIVERED', 0, NOW(6), NOW(6))", RF_SIBLING_CLAIM_ID, RF_SIBLING_ITEM_ID, BUYER_ID);
        sql("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, refunded_at, created_at, updated_at) "
                + "VALUES (?, 'rfn_rci_0052', ?, ?, ?, 'COMPLETED', 'rci_rfn_0002', NOW(6), NOW(6), NOW(6))",
                SIBLING_REFUND_ID, RF_SIBLING_CLAIM_ID, RF_PAYMENT_ID, amount);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'RECONCILIATION_ISSUE' AND target_id IN (SELECT id FROM reconciliation_issue "
                    + "WHERE dedupe_key LIKE 'payment-attempt:pat_rci_%' OR dedupe_key LIKE 'pg-refund:rci_rfn_%')");
            jdbc.update("DELETE FROM reconciliation_issue WHERE order_id IN (?, ?) OR dedupe_key LIKE 'payment-attempt:pat_rci_%' "
                    + "OR dedupe_key LIKE 'pg-refund:rci_rfn_%'", PAY_ORDER_ID, RF_ORDER_ID);
            jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER_ID);
            jdbc.update("DELETE FROM inventory_history WHERE inventory_id IN (?, ?)", PAY_VARIANT_ID, RF_VARIANT_ID);
            jdbc.update("DELETE FROM inventory WHERE id IN (?, ?)", PAY_VARIANT_ID, RF_VARIANT_ID);
            jdbc.update("DELETE FROM refund WHERE id IN (?, ?)", REFUND_ID, SIBLING_REFUND_ID);
            jdbc.update("DELETE FROM claim WHERE id IN (?, ?)", RF_CLAIM_ID, RF_SIBLING_CLAIM_ID);
            jdbc.update("DELETE FROM payment WHERE id IN (?, ?, ?, ?)", PAYMENT_ID, SECOND_PAYMENT_ID, OTHER_ORDER_PAYMENT_ID, RF_PAYMENT_ID);
            jdbc.update("DELETE FROM order_item WHERE order_id IN (?, ?)", PAY_ORDER_ID, RF_ORDER_ID);
            jdbc.update("DELETE FROM `order` WHERE id IN (?, ?)", PAY_ORDER_ID, RF_ORDER_ID);
            jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_ID);
        });
    }

    /** 매칭 없는 통지 행(접두어 = 통지 키까지·종류 접미 무관) 1행의 "상태|처리자|메모". */
    private String unmatchedIssueState(String keyPrefix) {
        return jdbc.queryForObject("SELECT CONCAT(status, '|', COALESCE(resolved_by, 'NULL'), '|', COALESCE(resolution_memo, 'NULL')) "
                + "FROM reconciliation_issue WHERE issue_type = 'PG_UNMATCHED_CALLBACK' AND dedupe_key LIKE CONCAT(?, '%')", String.class, keyPrefix);
    }

    /** 매칭 없는 통지 행의 SYSTEM 해소 감사 수. */
    private int systemResolveAuditCount(String keyPrefix) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'RECONCILIATION_ISSUE' AND actor_role = 'SYSTEM' "
                + "AND target_id IN (SELECT id FROM reconciliation_issue WHERE dedupe_key LIKE CONCAT(?, '%'))", Integer.class, keyPrefix);
    }

    private int issueCount(String type, String dedupeKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_issue WHERE issue_type = ? AND dedupe_key = ? AND status = 'OPEN'",
                Integer.class, type, dedupeKey);
    }

    private Long issueOrderId(String type, String dedupeKey) {
        return jdbc.queryForObject("SELECT order_id FROM reconciliation_issue WHERE issue_type = ? AND dedupe_key = ?",
                Long.class, type, dedupeKey);
    }

    private String paymentStatus(long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String refundStatus(long refundId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, refundId);
    }

    private String claimStatus(long claimId) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, orderId);
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, itemId);
    }

    private int reserved(long variantId) {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE variant_id = ?", Integer.class, variantId);
    }
}

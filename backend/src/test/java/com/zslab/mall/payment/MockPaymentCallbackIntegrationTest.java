package com.zslab.mall.payment;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * mock 결제 콜백 endpoint 통합 테스트(Track 93 D-198·실 MariaDB). {@code POST /api/v1/payments/mock-callback}의 인가 경계(401·403은
 * SecurityConfig·404 은닉)·서버 생성 필드(provider·pgTid)·기존 handleCallback 재사용(SUCCESS 전이·CANCEL×PAID 422·멱등)을 실 커밋 경로로
 * 검증한다. 시드·검증 방식은 {@link PaymentWebhookIntegrationTest} 패턴(TransactionTemplate + FK=0·JdbcTemplate 직접 조회)을 따른다.
 */
@AutoConfigureMockMvc
class MockPaymentCallbackIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 8101L;
    private static final long OTHER_BUYER_ID = 8102L;
    private static final long ORDER_ID = 8101L;
    private static final long ORDER_ITEM_ID = 8101L;
    private static final long PAYMENT_ID = 8101L;
    private static final long INVENTORY_ID = 8101L;
    private static final long VARIANT_ID = 8101L;
    private static final long AMOUNT = 10_000L;
    private static final String ATTEMPT_KEY = "pat_track93_it_0001";
    private static final String ENDPOINT = "/api/v1/payments/mock-callback";

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
        seed();
    }

    @AfterEach
    void tearDown() {
        // order_item 전컬럼 UPDATE가 orphaned FK(seller_id)를 참조하므로 FK=0을 테스트 전체에 유지하고 마지막에 복원한다(LT-02).
        try {
            cleanup();
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    @DisplayName("본인 주문 SUCCESS → 200·Payment PAID(provider MOCK_PG·pgTid mocktid_ 서버 생성)·Order PAID·재고 확정")
    void ownOrder_success_200() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SUCCESS")))
                .andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(pgProvider()).isEqualTo("MOCK_PG");
        assertThat(pgTid()).startsWith("mocktid_");
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(onHand()).isEqualTo(9);
        assertThat(reserved()).isZero();
    }

    @Test
    @DisplayName("타인 주문 → 404(은닉)·Payment PENDING 유지")
    void otherBuyer_404() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .headers(authHeaders.buyer(OTHER_BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SUCCESS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(reserved()).isEqualTo(1);
    }

    @Test
    @DisplayName("무인증 → 401·Payment PENDING 유지")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SUCCESS")))
                .andExpect(status().isUnauthorized());

        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("존재하지 않는 attemptKey → 404(은닉)")
    void unknownAttemptKey_404() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SUCCESS").replace(ATTEMPT_KEY, "pat_track93_unknown_001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("CANCEL×PAID → 422 INVALID_CALLBACK·Payment PAID 유지·환불 행 없음(mock 경로도 동일 봉쇄)")
    void cancelOnPaid_422() throws Exception {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE payment SET status = 'PAID', pg_provider = 'MOCK_PG', pg_tid = 'mocktid_track93_paid', "
                    + "paid_at = NOW(6) WHERE id = ?", PAYMENT_ID);
            jdbc.update("UPDATE `order` SET status = 'PAID' WHERE id = ?", ORDER_ID);
        });

        mockMvc.perform(post(ENDPOINT)
                        .headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CANCEL")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));

        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(refundCount()).isZero();
    }

    @ParameterizedTest(name = "{0} 결제 × SUCCESS → 422 INVALID_CALLBACK·상태 무변경·재고 불변(종결 상태에서 PAID 부활 없음·외부 검토 r1 정보 부족 확정)")
    @ValueSource(strings = {"EXPIRED", "FAILED", "CANCELLED"})
    void terminalPayment_success_422(String terminalStatus) throws Exception {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE payment SET status = ? WHERE id = ?", terminalStatus, PAYMENT_ID);
            jdbc.update("UPDATE `order` SET status = 'PAYMENT_EXPIRED' WHERE id = ?", ORDER_ID);
        });

        mockMvc.perform(post(ENDPOINT)
                        .headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SUCCESS")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));

        assertThat(paymentStatus()).isEqualTo(terminalStatus);
        assertThat(pgTid()).isNull();
        assertThat(orderStatus()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(onHand()).isEqualTo(10);
        assertThat(reserved()).isEqualTo(1);
        assertThat(inventoryHistoryCount()).isZero();
    }

    @Test
    @DisplayName("SUCCESS 2회 → 2회차 200 멱등 NO-OP·재고 차감 1회(history 1)·결제완료 알림 1회·Order PAID 유지")
    void success_twice_idempotent() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SUCCESS")))
                .andExpect(status().isOk());
        String firstPgTid = pgTid();

        mockMvc.perform(post(ENDPOINT)
                        .headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SUCCESS")))
                .andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(pgTid()).isEqualTo(firstPgTid);            // NO-OP: 2회차 서버 생성 pgTid로 덮어쓰지 않음
        assertThat(onHand()).isEqualTo(9);
        assertThat(reserved()).isZero();
        assertThat(inventoryHistoryCount()).isEqualTo(1);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(orderStatus()).isEqualTo("PAID");            // 외부 검토 r2 수용: 2회차 NO-OP가 주문 상태를 건드리지 않음
    }

    @Test
    @DisplayName("callbackType 누락 → 400 VALIDATION_FAILED")
    void missingCallbackType_400() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptKey\": \"" + ATTEMPT_KEY + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String body(String callbackType) {
        return "{\"attemptKey\": \"" + ATTEMPT_KEY + "\", \"callbackType\": \"" + callbackType + "\"}";
    }

    // 모든 시드 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** user 2명(본인·타인)·order(PENDING_PAYMENT·buyer 본인)·order_item(ORDERED)·payment(PENDING)·inventory(reserved=1)를 시드한다. */
    private void seed() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, 'usr_track93_it_00001', NOW(6), NOW(6))",
                    BUYER_ID);
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, 'usr_track93_it_00002', NOW(6), NOW(6))",
                    OTHER_BUYER_ID);
            jdbc.update("INSERT INTO `order` "
                    + "(id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "ordered_at, created_at, updated_at) "
                    + "VALUES (?, 'ord_track93_it_0001', ?, 'ORD20260921-T93I1', 'PENDING_PAYMENT', ?, 0, 0, "
                    + "NOW(6), NOW(6), NOW(6))",
                    ORDER_ID, BUYER_ID, AMOUNT);
            jdbc.update("INSERT INTO order_item "
                    + "(id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                    + "item_status, created_at, updated_at, product_name, commission_rate) "
                    + "VALUES (?, 'oit_track93_it_0001', ?, 1, ?, 1, 1, ?, ?, 'ORDERED', NOW(6), NOW(6), '테스트 상품', 1000)",
                    ORDER_ITEM_ID, ORDER_ID, VARIANT_ID, AMOUNT, AMOUNT);
            jdbc.update("INSERT INTO payment "
                    + "(id, public_id, order_id, method, amount, status, payment_attempt_key, created_at, updated_at) "
                    + "VALUES (?, 'pay_track93_it_0001', ?, 'CARD', ?, 'PENDING', ?, NOW(6), NOW(6))",
                    PAYMENT_ID, ORDER_ID, AMOUNT, ATTEMPT_KEY);
            jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                            + "created_at, updated_at) VALUES (?, ?, 10, 1, 9, NOW(6), NOW(6))",
                    INVENTORY_ID, VARIANT_ID);
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER_ID);
            jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
            jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
            jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
            jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
            jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
            jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", BUYER_ID, OTHER_BUYER_ID);
        });
    }

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }

    private String pgProvider() {
        return jdbc.queryForObject("SELECT pg_provider FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }

    private String pgTid() {
        return jdbc.queryForObject("SELECT pg_tid FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int inventoryHistoryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ?", Integer.class, INVENTORY_ID);
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ?", Integer.class, BUYER_ID);
    }

    private int refundCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE payment_id = ?", Integer.class, PAYMENT_ID);
    }
}

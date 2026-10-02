package com.zslab.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 완료 실패 경로(자체 평점 5-1) 통합 테스트(실 MariaDB·MockMvc). {@code MockPaymentCallbackIntegrationTest#terminalPayment_success_422}의
 * 조건(종결 결제 × SUCCESS 콜백)을 재현해, 응답·결제·주문·품목·재고 불변에 더해 결제 완료 알림(PaymentCompleted AFTER_COMMIT 소비) 미발생을 단언한다.
 *
 * <p><b>트랜잭션</b>: {@code @Transactional}을 두지 않는다(커밋 경로). 시드/정리는 {@link TransactionTemplate} +
 * {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally) — 실패 경로는 앱이 order_item을 쓰지 않아 FK를 테스트 전체에 끌 필요가 없다.
 */
@AutoConfigureMockMvc
class MockPaymentCallbackRefusalSideEffectIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 9460L;
    private static final long ORDER_ID = 9460L;
    private static final long ORDER_ITEM_ID = 9460L;
    private static final long PAYMENT_ID = 9460L;
    private static final long INVENTORY_ID = 9460L;
    private static final long VARIANT_ID = 9460L;
    private static final long DUMMY_FK_ID = 9460L;
    private static final long AMOUNT = 10_000L;
    private static final int INITIAL_ON_HAND = 10;
    private static final int RESERVED = 1;
    private static final String ATTEMPT_KEY = "pat_a2b_cb_0001";
    private static final String ENDPOINT = "/api/v1/payments/mock-callback";
    private static final String SUCCESS_BODY = "{\"attemptKey\": \"" + ATTEMPT_KEY + "\", \"callbackType\": \"SUCCESS\"}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
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

    @ParameterizedTest(name = "P1 결제 완료 실패: {0} 결제 × SUCCESS → 422 INVALID_CALLBACK · 결제·주문·품목 불변 · 재고 불변 · 결제 완료 알림 0")
    @ValueSource(strings = {"EXPIRED", "FAILED", "CANCELLED"})
    void terminalPayment_success_refused_noSideEffects(String terminalStatus) throws Exception {
        seed(terminalStatus);

        mockMvc.perform(post(ENDPOINT).with(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SUCCESS_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));

        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT_ID)).isEqualTo(terminalStatus);
        assertThat(jdbc.queryForObject("SELECT pg_tid FROM payment WHERE id = ?", String.class, PAYMENT_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID)).isEqualTo("ORDERED");
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID))
                .isEqualTo(INITIAL_ON_HAND);
        assertThat(jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID))
                .isEqualTo(RESERVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ?", Integer.class, INVENTORY_ID))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ? AND template_code = ?",
                Integer.class, BUYER_ID, NotificationTemplateCodes.PAYMENT_COMPLETED)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ?", Integer.class, BUYER_ID))
                .isZero();
    }

    // 모든 시드·조회 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** buyer·order(PAYMENT_EXPIRED)·order_item(ORDERED)·payment(종결 상태·pg_tid 없음)·inventory(10/1/9). */
    private void seed(String paymentStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "A2BCBUSR"));
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                                + "ordered_at, created_at, updated_at) VALUES (?, ?, ?, 'ORDA2BCB', 'PAYMENT_EXPIRED', ?, 0, 0, "
                                + "NOW(6), NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "A2BCBORD"), BUYER_ID, AMOUNT);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                                + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'ORDERED', NOW(6), NOW(6), 'A2B결제상품', 1000)",
                        ORDER_ITEM_ID, pid("oit_", "A2BCBOIT"), ORDER_ID, DUMMY_FK_ID, VARIANT_ID, DUMMY_FK_ID, AMOUNT, AMOUNT);
                jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, created_at, "
                                + "updated_at) VALUES (?, ?, ?, 'CARD', ?, ?, ?, NOW(6), NOW(6))",
                        PAYMENT_ID, pid("pay_", "A2BCBPAY"), ORDER_ID, AMOUNT, paymentStatus, ATTEMPT_KEY);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID, INITIAL_ON_HAND, RESERVED, INITIAL_ON_HAND - RESERVED);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

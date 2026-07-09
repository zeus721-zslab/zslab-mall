package com.zslab.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 결제 webhook end-to-end 통합 테스트(실 MariaDB·Flyway V1~V5·GAP-E2E-2). Order 생성→Payment.PENDING 시딩→
 * POST /api/webhooks/payments (SUCCESS 콜백)→Payment.PAID·OrderItem.PAID·Order.PAID 전이를 실 커밋 경로로 검증한다.
 *
 * <p><b>트랜잭션</b>: PaymentService.handleCallback은 동기 @EventListener(OrderEventHandler)와 동일 트랜잭션에서 Order·
 * OrderItem 상태를 전이하므로 클래스에 {@code @Transactional}을 두지 않는다(RefundWebhookIntegrationTest 패턴 준용).
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}으로 상위 그래프(user) 없이 커밋하고,
 * 검증은 {@link JdbcTemplate} 직접 조회(1차 캐시 무관)로 한다.
 */
@AutoConfigureMockMvc
class PaymentWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final long ORDER_ID = 8001L;
    private static final long ORDER_ITEM_ID = 8001L;
    private static final long PAYMENT_ID = 8001L;
    private static final long INVENTORY_ID = 8001L;
    private static final long VARIANT_ID = 1L;   // 시드 order_item.variant_id와 일치(재고 해제 대상)
    private static final long AMOUNT = 10_000L;
    private static final String ATTEMPT_KEY = "pat_track6_it_0001";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        // 웹훅 처리 중 order_item Hibernate 전컬럼 UPDATE가 orphaned FK(seller_id=1)를 참조하므로
        // FK=0은 BeforeEach~Test 전체에 유지되어야 하며, 최종 tearDown 후 커넥션 반환 전 복원한다(LT-02).
        try {
            cleanup();
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    @DisplayName("webhook SUCCESS e2e (GAP-E2E-2): Order→Payment.PENDING 시딩→콜백→Payment.PAID·OrderItem.PAID·Order.PAID")
    void webhook_success_endToEnd() throws Exception {
        String body = "{"
                + "\"provider\": \"MOCK_PG\","
                + "\"callbackType\": \"SUCCESS\","
                + "\"paymentAttemptKey\": \"" + ATTEMPT_KEY + "\","
                + "\"pgTid\": \"tid_track6_it_0001\","
                + "\"occurredAt\": \"2026-06-28T00:00:00\""
                + "}";

        mockMvc.perform(post("/api/webhooks/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(orderItemStatus()).isEqualTo("PAID");
        assertThat(orderStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("webhook FAILURE e2e (FE-12c 정정): 콜백→Payment.FAILED(PG_FAILURE)·Order.PAYMENT_EXPIRED·OrderItem 무변경·재고 예약 해제")
    void webhook_failure_endToEnd() throws Exception {
        seedInventory(10, 1, 9);   // reserved=1(order_item.quantity=1) → 종료 후 OrderTerminated 소비로 해제 기대

        String body = "{"
                + "\"provider\": \"MOCK_PG\","
                + "\"callbackType\": \"FAILURE\","
                + "\"paymentAttemptKey\": \"" + ATTEMPT_KEY + "\","
                + "\"occurredAt\": \"2026-06-28T00:00:00\""
                + "}";

        mockMvc.perform(post("/api/webhooks/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("FAILED");                 // PG 실제 결제 실패(EXPIRED와 구분)
        assertThat(failureCode()).isEqualTo("PG_FAILURE");              // metadata 미제공 → 기본값
        assertThat(orderStatus()).isEqualTo("PAYMENT_EXPIRED");         // Order는 미결제 종료
        assertThat(orderItemStatus()).isEqualTo("ORDERED");            // OrderItem 무변경(재고 해제는 variant_id 기반)
        assertThat(reserved()).isZero();                               // AFTER_COMMIT OrderTerminated → 예약 해제
    }

    /** order(PENDING_PAYMENT)·order_item(ORDERED)·payment(PENDING)을 고정 id로 시드한다(FK 비활성·상위 그래프 생략). */
    private void seed() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("INSERT INTO `order` "
                    + "(id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "ordered_at, created_at, updated_at) "
                    + "VALUES (?, 'ord_track6_it_0001', 8001, 'ORD20260628-T6I01', 'PENDING_PAYMENT', ?, 0, 0, "
                    + "NOW(6), NOW(6), NOW(6))",
                    ORDER_ID, AMOUNT);
            jdbc.update("INSERT INTO order_item "
                    + "(id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                    + "item_status, created_at, updated_at) "
                    + "VALUES (?, 'oit_track6_it_0001', ?, 1, 1, 1, 1, ?, ?, 'ORDERED', NOW(6), NOW(6))",
                    ORDER_ITEM_ID, ORDER_ID, AMOUNT, AMOUNT);
            jdbc.update("INSERT INTO payment "
                    + "(id, public_id, order_id, method, amount, status, payment_attempt_key, created_at, updated_at) "
                    + "VALUES (?, 'pay_track6_it_0001', ?, 'CARD', ?, 'PENDING', ?, NOW(6), NOW(6))",
                    PAYMENT_ID, ORDER_ID, AMOUNT, ATTEMPT_KEY);
        });
    }

    /** 재고 행을 시드한다(FAILURE 테스트 전용·on_hand·reserved·available 지정). 모든 변수 ? 바인딩. */
    private void seedInventory(int onHand, int reserved, int available) {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                    INVENTORY_ID, VARIANT_ID, onHand, reserved, available);
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
            jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
            jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
            jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
            jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
        });
    }

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }

    private String failureCode() {
        return jdbc.queryForObject("SELECT failure_code FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    private String orderItemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }
}

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
 * 결제 webhook end-to-end 통합 테스트(실 MariaDB·Flyway V1~V5·GAP-E2E-2). Order 생성→Payment.PENDING 시딩→
 * POST /api/webhooks/payments (SUCCESS 콜백)→Payment.PAID·OrderItem.PAID·Order.PAID 전이를 실 커밋 경로로 검증한다.
 *
 * <p><b>트랜잭션</b>: PaymentService.handleCallback은 동기 @EventListener(OrderEventHandler)와 동일 트랜잭션에서 Order·
 * OrderItem 상태를 전이하므로 클래스에 {@code @Transactional}을 두지 않는다(RefundWebhookIntegrationTest 패턴 준용).
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}으로 상위 그래프(user) 없이 커밋하고,
 * 검증은 {@link JdbcTemplate} 직접 조회(1차 캐시 무관)로 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentWebhookIntegrationTest {

    private static final long ORDER_ID = 8001L;
    private static final long ORDER_ITEM_ID = 8001L;
    private static final long PAYMENT_ID = 8001L;
    private static final long AMOUNT = 10_000L;
    private static final String ATTEMPT_KEY = "pat_track6_it_0001";

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

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
            jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
            jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
        });
    }

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }

    private String orderItemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }
}

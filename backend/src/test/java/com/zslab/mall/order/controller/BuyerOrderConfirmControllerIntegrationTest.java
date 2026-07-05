package com.zslab.mall.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Buyer 구매확정 endpoint E2E 통합 테스트(Track 47·실 MariaDB). HTTP → {@code BuyerOrderController.confirmPurchase} →
 * {@code BuyerOrderConfirmService.confirmPurchase} → 소유권 검증 → OrderItem DELIVERED→CONFIRMED 전이 → Order.status 재계산 →
 * DB 흐름을 실 커밋·HTTP 경유로 검증한다(라이브 트랩 차단·{@code SellerDeliveryCompletionControllerIntegrationTest} 패턴 1:1).
 *
 * <p><b>커버</b>: T1 401(무인증)·T2 200(소유 buyer·DELIVERED→CONFIRMED·Order CONFIRMED)·T3 404(타 buyer 존재 은닉)·
 * T4 422(비-DELIVERED SHIPPING 품목)·T5 200 멱등(이미 CONFIRMED·no-op).
 *
 * <p><b>트랜잭션</b>: 실 커밋으로 전이·재계산을 구동하므로 클래스에 {@code @Transactional}을 두지 않는다. 시드/정리는
 * {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), 검증은 {@link JdbcTemplate}로 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuyerOrderConfirmControllerIntegrationTest {

    private static final long BUYER_USER = 9470L;   // 주문 소유 buyer(order.buyer_id)
    private static final long OTHER_BUYER = 9471L;   // 타 buyer(cross-tenant)
    private static final long SELLER_ID = 9470L;
    private static final long PRODUCT_ID = 9470L;
    private static final long VARIANT_ID = 9470L;
    private static final long ORDER_ID = 9470L;
    private static final long ORDER_ITEM_ID = 9470L;
    private static final long DUMMY_FK_ID = 9470L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String ORDER_PID = pid("ord_", "BOCORD");
    private static final String ITEM_PID = pid("oit_", "BOCOIT");
    private static final String CONFIRM_URL =
            "/api/v1/orders/" + ORDER_PID + "/items/" + ITEM_PID + "/confirm";

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

    @Test
    @DisplayName("T1 헤더 누락: 인증 없음 → 401 UNAUTHENTICATED·OrderItem DELIVERED 무변경")
    void confirm_missingAuth_returns401() throws Exception {
        seedGraph("DELIVERED");

        mockMvc.perform(post(CONFIRM_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(itemStatus()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("T2 정상: 소유 buyer + DELIVERED → 200·CONFIRMED·OrderItem/Order CONFIRMED")
    void confirm_ownerBuyerDelivered_returns200() throws Exception {
        seedGraph("DELIVERED");

        mockMvc.perform(post(CONFIRM_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderItemId").value(ITEM_PID))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(itemStatus()).isEqualTo("CONFIRMED");
        assertThat(orderStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("T3 cross-tenant: 타 buyer → 404 ORDER_NOT_FOUND(존재 은닉)·OrderItem DELIVERED 무변경")
    void confirm_crossTenant_returns404() throws Exception {
        seedGraph("DELIVERED");

        mockMvc.perform(post(CONFIRM_URL).headers(authHeaders.buyer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        assertThat(itemStatus()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("T4 비-DELIVERED: SHIPPING 품목 → 422 ORDER_ITEM_INVALID_STATE·OrderItem SHIPPING 무변경")
    void confirm_nonDeliveredItem_returns422() throws Exception {
        seedGraph("SHIPPING");

        mockMvc.perform(post(CONFIRM_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_INVALID_STATE"));

        assertThat(itemStatus()).isEqualTo("SHIPPING");
    }

    @Test
    @DisplayName("T5 멱등: 이미 CONFIRMED → 200·no-op·CONFIRMED 유지")
    void confirm_alreadyConfirmed_returns200Idempotent() throws Exception {
        seedGraph("CONFIRMED");

        mockMvc.perform(post(CONFIRM_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(itemStatus()).isEqualTo("CONFIRMED");
    }

    // ---------- seed·helpers (SellerDeliveryCompletionControllerIntegrationTest 패턴 1:1) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * 구매확정 대상 그래프를 시드한다. order.status·order_item.item_status를 {@code itemStatus}로 맞춘다(단일 항목 정합).
     * delivery는 확정 경로가 참조하지 않으므로 시드하지 않는다(order_item 상태만으로 전이 판정).
     */
    private void seedGraph(String itemStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_USER, pid("usr_", "BOCUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙47셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BOCSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙47상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "BOCPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCBOC', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "BOCVAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, ORDER_PID, BUYER_USER, "ORDBOC" + ORDER_ID, itemStatus, ITEM_PRICE);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                        ORDER_ITEM_ID, ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                        ITEM_PRICE, ITEM_PRICE, itemStatus);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_USER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

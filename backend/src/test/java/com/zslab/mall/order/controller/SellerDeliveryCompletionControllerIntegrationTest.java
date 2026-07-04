package com.zslab.mall.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.delivery.event.DeliveryCompleted;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Seller 일반 주문 배송 완료 endpoint E2E 통합 테스트(Track 43·M1·M3·실 MariaDB). HTTP → {@code SellerDeliveryCompletionController} →
 * {@code OrderShippingService.markDeliveredBySeller} → 소유권 검증 → primitive {@code markDelivered}(E5) → {@code DeliveryCompletedHandler}
 * (OrderItem DELIVERED·Order 재계산) → DB 흐름을 실 커밋·HTTP 경유로 검증한다(라이브 트랩 차단·{@code SellerShippingControllerIntegrationTest} 패턴 1:1).
 *
 * <p><b>일반 배송(claim_id NULL·M3)</b>: 기존 HTTP markDelivered 커버는 EXCHANGE 배송뿐이었다(AdminDeliveryControllerIntegrationTest T5).
 * 본 테스트는 claim_id NULL 일반 배송의 HTTP markDelivered를 처음으로 커버한다(DeliveryCompletedHandler 일반 경로·OrderItem SHIPPING→DELIVERED).
 *
 * <p><b>커버</b>: T1 401(무인증)·T2 200(소유 셀러·SHIPPING→DELIVERED·E5·OrderItem/Order DELIVERED)·T3 404(cross-tenant 존재 은닉)·
 * T4 422(비-SHIPPING READY 배송·M4).
 *
 * <p><b>트랜잭션</b>: E5 동기 소비·AFTER_COMMIT 알림 핸들러를 실 커밋으로 구동하므로 클래스에 {@code @Transactional}을 두지 않는다.
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), 검증은 {@link JdbcTemplate}·이벤트는 {@link ApplicationEvents}로 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class SellerDeliveryCompletionControllerIntegrationTest {

    private static final long USER_ID = 9440L; // 구매자(배송 알림 recipient)
    private static final long SELLER_A = 9440L; // 품목 소유 셀러
    private static final long SELLER_B = 9441L; // 타 셀러(cross-tenant)
    // actorId(JWT subject)를 seller_id와 다른 값으로 둔다 — user.id==seller.id 우연일치 은폐 제거(SellerShippingControllerIntegrationTest 정합).
    private static final long SELLER_A_USER = 9442L;
    private static final long SELLER_B_USER = 9443L;
    private static final long PRODUCT_ID = 9440L;
    private static final long VARIANT_ID = 9440L;
    private static final long ORDER_ID = 9440L;
    private static final long ORDER_ITEM_ID = 9440L;
    private static final long DELIVERY_ID = 9440L;
    private static final long DUMMY_FK_ID = 9440L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String DELIVERY_PID = pid("dlv_", "SDCDLV");
    private static final String TRACKING_NO = "CJ-SDC-0001";
    private static final String MARK_URL = "/api/v1/deliveries/" + DELIVERY_PID + "/mark-delivered";

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
    private ApplicationEvents events;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedSellerUsers();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 헤더 누락: 인증 없음 → 401 UNAUTHENTICATED·Delivery SHIPPING 무변경·DeliveryCompleted 0")
    void markDelivered_missingAuth_returns401() throws Exception {
        seedGraph("SHIPPING", "SHIPPING");

        mockMvc.perform(post(MARK_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(deliveryStatus()).isEqualTo("SHIPPING");
        assertThat(itemStatus()).isEqualTo("SHIPPING");
        assertThat(events.stream(DeliveryCompleted.class).count()).isZero();
    }

    @Test
    @DisplayName("T2 정상: 소유 셀러 + SHIPPING 일반 배송(claim_id NULL) → 200·DELIVERED·OrderItem/Order DELIVERED·DeliveryCompleted 1회")
    void markDelivered_ownerSellerShippingDelivery_returns200() throws Exception {
        seedGraph("SHIPPING", "SHIPPING");

        mockMvc.perform(post(MARK_URL).headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryPublicId").value(DELIVERY_PID))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.carrier").value("CJ"))
                .andExpect(jsonPath("$.trackingNo").value(TRACKING_NO));

        assertThat(deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(itemStatus()).isEqualTo("DELIVERED");
        assertThat(orderStatus()).isEqualTo("DELIVERED");
        assertThat(events.stream(DeliveryCompleted.class).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("T3 cross-tenant: 타 셀러 → 404 DELIVERY_NOT_FOUND(존재 은닉)·Delivery SHIPPING 무변경·DeliveryCompleted 0")
    void markDelivered_crossTenant_returns404() throws Exception {
        seedGraph("SHIPPING", "SHIPPING");

        mockMvc.perform(post(MARK_URL).headers(authHeaders.seller(SELLER_B_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));

        assertThat(deliveryStatus()).isEqualTo("SHIPPING");
        assertThat(itemStatus()).isEqualTo("SHIPPING");
        assertThat(events.stream(DeliveryCompleted.class).count()).isZero();
    }

    @Test
    @DisplayName("T4 비-SHIPPING: READY 배송 → 422 DELIVERY_INVALID_STATE·Delivery READY 무변경·DeliveryCompleted 0")
    void markDelivered_nonShippingDelivery_returns422() throws Exception {
        seedGraph("READY", "PREPARING");

        mockMvc.perform(post(MARK_URL).headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELIVERY_INVALID_STATE"));

        assertThat(deliveryStatus()).isEqualTo("READY");
        assertThat(itemStatus()).isEqualTo("PREPARING");
        assertThat(events.stream(DeliveryCompleted.class).count()).isZero();
    }

    // ---------- seed·helpers (SellerShippingControllerIntegrationTest 패턴 1:1) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    // resolver 해소용 seller_user 실 매핑 시드(actorId≠seller_id·FK_CHECKS=0라 user/seller 행 부재 허용·role_id=SELLER_OWNER seed).
    private void seedSellerUsers() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        SELLER_A_USER, SELLER_A);
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        SELLER_B_USER, SELLER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /**
     * 일반 배송(claim_id NULL) 그래프를 시드한다. SHIPPING이면 tracking_no·shipped_at을 함께 넣어 DLV-1·DLV-3을 만족시키고,
     * READY이면 미발송 상태(tracking_no·shipped_at NULL)로 둔다(비-SHIPPING 422 유발).
     */
    private void seedGraph(String deliveryStatus, String itemStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "SDCUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙43셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_A, pid("slr_", "SDCSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙43상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "SDCPRD"), SELLER_A, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCSDC', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "SDCVAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "SDCORD"), USER_ID, "ORDSDC" + ORDER_ID, itemStatus, ITEM_PRICE);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                        ORDER_ITEM_ID, pid("oit_", "SDCOIT"), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_A,
                        ITEM_PRICE, ITEM_PRICE, itemStatus);
                if ("READY".equals(deliveryStatus)) {
                    jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, status, created_at, updated_at) "
                                    + "VALUES (?, ?, ?, 'CJ', 'READY', NOW(6), NOW(6))",
                            DELIVERY_ID, DELIVERY_PID, ORDER_ITEM_ID);
                } else {
                    jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, tracking_no, status, "
                                    + "shipped_at, created_at, updated_at) VALUES (?, ?, ?, 'CJ', ?, ?, NOW(6), NOW(6), NOW(6))",
                            DELIVERY_ID, DELIVERY_PID, ORDER_ITEM_ID, TRACKING_NO, deliveryStatus);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", SELLER_A_USER, SELLER_B_USER);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_A);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String deliveryStatus() {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE order_item_id = ?", String.class, ORDER_ITEM_ID);
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

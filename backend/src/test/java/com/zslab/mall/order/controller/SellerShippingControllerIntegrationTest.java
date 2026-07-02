package com.zslab.mall.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.delivery.event.DeliveryStarted;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Seller 일반 주문 배송 개시 endpoint E2E 통합 테스트(Track 23·실 MariaDB). HTTP → {@code SellerShippingController} →
 * {@code OrderShippingService.prepareShipment} → OrderItem PAID→PREPARING → Delivery 생성·markShipping(E4) → DB 흐름을
 * 실 커밋·HTTP 경유로 검증한다(라이브 트랩 차단). 401 헤더 누락·404 cross-tenant·200 정상·422 비-PAID 상태를 커버한다.
 *
 * <p><b>트랜잭션</b>: DeliveryStarted 동기 소비·AFTER_COMMIT 알림 핸들러를 실제 커밋으로 구동하므로 클래스에 {@code @Transactional}을
 * 두지 않는다(SellerDeliveryIntegrationTest 패턴 1:1). 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}
 * (LT-02 try-finally), 검증은 {@link JdbcTemplate}·이벤트는 {@link ApplicationEvents}로 한다.
 *
 * <p><b>422 트리거 실측</b>: prepareShipment 경로에서 markShipping은 방금 생성한 READY Delivery만 대상(READY→SHIPPING 항상 합법)이라
 * 전이 위반 미도달이다. 실재 422는 비-PAID OrderItem의 changeToPreparing(PAID→PREPARING) 위반뿐이므로 T4는 이미 SHIPPING인 품목으로
 * 유발한다(미도달 예외 선제 테스트 금지·기조 4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class SellerShippingControllerIntegrationTest {

    private static final String SELLER_ID_HEADER = "X-Seller-Id";

    private static final long USER_ID = 9424L;
    private static final long SELLER_A = 9424L; // 품목 소유 셀러
    private static final long SELLER_B = 9425L; // 타 셀러(cross-tenant)
    private static final long PRODUCT_ID = 9424L;
    private static final long VARIANT_ID = 9424L;
    private static final long ORDER_ID = 9424L;
    private static final long ORDER_ITEM_ID = 9424L;
    private static final long DUMMY_FK_ID = 9424L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String ORDER_ITEM_PID = pid("oit_", "SSCOIT");
    private static final String TRACKING_NO = "CJ-SSC-0001";
    private static final String PREPARE_URL = "/api/v1/order-items/" + ORDER_ITEM_PID + "/prepare-shipment";

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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 정상: 소유 셀러 + PAID 품목 → 200·Delivery SHIPPING(claim_id NULL)·OrderItem/Order SHIPPING·DeliveryStarted 1회")
    void prepareShipment_ownerSellerPaidItem_returns200() throws Exception {
        seedItem("PAID", "PAID");

        mockMvc.perform(post(PREPARE_URL)
                        .header(SELLER_ID_HEADER, String.valueOf(SELLER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryPublicId").exists())
                .andExpect(jsonPath("$.status").value("SHIPPING"))
                .andExpect(jsonPath("$.carrier").value("CJ"))
                .andExpect(jsonPath("$.trackingNo").value(TRACKING_NO));

        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(deliveryStatus()).isEqualTo("SHIPPING");
        assertThat(deliveryClaimId()).isNull();
        assertThat(itemStatus()).isEqualTo("SHIPPING");
        assertThat(orderStatus()).isEqualTo("SHIPPING");
        assertThat(events.stream(DeliveryStarted.class).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("T2 헤더 누락: X-Seller-Id 부재 → 401 UNAUTHENTICATED·Delivery 미생성·OrderItem PAID 무변경·이벤트 0")
    void prepareShipment_missingHeader_returns401() throws Exception {
        seedItem("PAID", "PAID");

        mockMvc.perform(post(PREPARE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(deliveryCount()).isZero();
        assertThat(itemStatus()).isEqualTo("PAID");
        assertThat(events.stream(DeliveryStarted.class).count()).isZero();
    }

    @Test
    @DisplayName("T3 cross-tenant: 타 셀러 → 404 ORDER_NOT_FOUND(존재 은닉)·Delivery 미생성·OrderItem PAID 무변경·이벤트 0")
    void prepareShipment_crossTenant_returns404() throws Exception {
        seedItem("PAID", "PAID");

        mockMvc.perform(post(PREPARE_URL)
                        .header(SELLER_ID_HEADER, String.valueOf(SELLER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        assertThat(deliveryCount()).isZero();
        assertThat(itemStatus()).isEqualTo("PAID");
        assertThat(events.stream(DeliveryStarted.class).count()).isZero();
    }

    @Test
    @DisplayName("T4 비-PAID 상태: 이미 SHIPPING 품목 → 422 DELIVERY_INVALID_STATE·Delivery 미생성·OrderItem SHIPPING 무변경·이벤트 0")
    void prepareShipment_nonPaidItem_returns422() throws Exception {
        seedItem("SHIPPING", "SHIPPING");

        mockMvc.perform(post(PREPARE_URL)
                        .header(SELLER_ID_HEADER, String.valueOf(SELLER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELIVERY_INVALID_STATE"));

        assertThat(deliveryCount()).isZero();
        assertThat(itemStatus()).isEqualTo("SHIPPING");
        assertThat(events.stream(DeliveryStarted.class).count()).isZero();
    }

    // ---------- seed·helpers (SellerDeliveryIntegrationTest 패턴 1:1) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedItem(String itemStatus, String orderStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "SSCUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙23셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_A, pid("slr_", "SSCSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙23상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "SSCPRD"), SELLER_A, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCSSC', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "SSCVAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "SSCORD"), USER_ID, "ORDSSC" + ORDER_ID, orderStatus, ITEM_PRICE);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                        ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_A,
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

    private String body(String carrier, String trackingNo) {
        return "{\"carrier\":\"" + carrier + "\",\"trackingNo\":\"" + trackingNo + "\"}";
    }

    private int deliveryCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery WHERE order_item_id = ?", Integer.class, ORDER_ITEM_ID);
        return count == null ? 0 : count;
    }

    private String deliveryStatus() {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE order_item_id = ?", String.class, ORDER_ITEM_ID);
    }

    private Long deliveryClaimId() {
        return jdbc.queryForObject("SELECT claim_id FROM delivery WHERE order_item_id = ?", Long.class, ORDER_ITEM_ID);
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

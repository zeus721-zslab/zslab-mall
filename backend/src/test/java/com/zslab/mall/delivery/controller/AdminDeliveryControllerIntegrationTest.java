package com.zslab.mall.delivery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.event.DeliveryStarted;
import com.zslab.mall.order.enums.OrderItemStatus;
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
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Admin Delivery endpoint E2E 통합 테스트(Track 18·Track 20·D-102·D-104·실 MariaDB). HTTP → {@code AdminDeliveryController} →
 * {@code DeliveryService} wrapper → primitive → DB 흐름을 실 커밋·HTTP 경유로 실측한다(라이브 트랩 차단·
 * {@link com.zslab.mall.delivery.integration.SellerDeliveryIntegrationTest} 1:1). 교환품 출고 등록(T1~T3)·배송 완료(T4~T6) 2 endpoint를 커버한다.
 *
 * <p><b>Admin 책임 경계(D-93 Q3·Q5)</b>: Admin은 전체 접근이므로 Seller 테스트의 cross-tenant 시나리오가 부재하다. 인증 헤더
 * 검증(401)·성공(200)·primitive 예외 전파(4xx) 3건만 보장한다(CLAUDE.md 신규 도메인 통합 테스트 3건 의무·D-102 §6).
 *
 * <p><b>Track 20 확장(D-104)</b>: 배송 완료 endpoint {@code POST /api/v1/admin/deliveries/{deliveryPublicId}/mark-delivered}
 * 3건(T4 401·T5 200 교환 배송 SHIPPING→DELIVERED + AFTER_COMMIT 체인·T6 404). wrapper {@code markDeliveredByAdmin} → primitive
 * {@code markDelivered} → E5 DeliveryCompleted → {@code ExchangeDeliveryCompletedHandler}(OrderItem EXCHANGED·Claim COMPLETED)까지 실 커밋 구동한다.
 *
 * <p><b>트랜잭션</b>: DeliveryStarted 동기 소비·AFTER_COMMIT 알림 핸들러를 실 커밋으로 구동하므로 클래스에 {@code @Transactional}을
 * 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), 검증은
 * {@link JdbcTemplate} 직접 조회·이벤트는 {@link ApplicationEvents}로 한다.
 *
 * <p><b>HTTP 경유 의무</b>: primitive를 직접 호출하지 않고 MockMvc로 endpoint를 구동한다. X-Admin-Id 헤더 stub은
 * {@code HeaderAdminActorResolver}가 해소하되 식별자는 사용하지 않는다(D-93 Q3·헤더 존재·형식 검증만).
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AdminDeliveryControllerIntegrationTest {

    private static final long ADMIN = 7001L; // Admin 액터 stub(전체 접근·검증 비대상)
    private static final long USER_ID = 8815L;
    private static final long SELLER_ID = 8815L; // 품목 소유 셀러(FK 부모 그래프·Admin 검증 비대상·D-91)
    private static final long PRODUCT_ID = 8815L;
    private static final long VARIANT_ID = 8815L;
    private static final long ORDER_ID = 8815L;
    private static final long ORDER_ITEM_ID = 8815L;
    private static final long CLAIM_ID = 8815L;
    private static final long DELIVERY_ID = 8815L; // 단일 Delivery(order_item_id FK·markDelivered 대상)
    private static final long DUMMY_FK_ID = 8815L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String CLAIM_PID = pid("clm_", "ADCLM");
    private static final String ORDER_ITEM_PID = pid("oit_", "ADOIT");
    private static final String DELIVERY_PID = pid("dlv_", "ADDLV");
    private static final String TRACKING_NO = "CJ-AD-0001";

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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인증 실패: X-Admin-Id 헤더 부재 → 401 UNAUTHENTICATED·Delivery 미생성·이벤트 0")
    void register_missingAdminHeader_returns401() throws Exception {
        // resolve()가 claim 조회 이전 최선두에서 throw하므로 시드 불요(claimPublicId 미존재여도 401이 우선한다).
        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(deliveryCount()).isZero();
        assertThat(events.stream(DeliveryStarted.class).count()).isZero();
    }

    @Test
    @DisplayName("T2 성공: 유효 X-Admin-Id + APPROVED EXCHANGE → 200·SHIPPING Delivery 커밋·claim_id 연결·DeliveryStarted 1회")
    void register_validAdmin_approvedExchangeClaim_returns200_persistsShippingDelivery() throws Exception {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.EXCHANGE_REQUESTED);
            seedApprovedClaim(ClaimType.EXCHANGE);
        });

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryPublicId").exists())
                .andExpect(jsonPath("$.status").value("SHIPPING"))
                .andExpect(jsonPath("$.carrier").value("CJ"))
                .andExpect(jsonPath("$.trackingNo").value(TRACKING_NO));

        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(deliveryStatus()).isEqualTo("SHIPPING");
        assertThat(deliveryClaimId()).isEqualTo(CLAIM_ID);
        assertThat(deliveryCarrier()).isEqualTo("CJ");
        assertThat(deliveryTrackingNo()).isEqualTo(TRACKING_NO);
        assertThat(events.stream(DeliveryStarted.class).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("T3 실패: RETURN 클레임(type 불일치) → 422 CLAIM_STATE_INVALID·primitive 예외 전파·Delivery 롤백")
    void register_returnTypeClaim_returns422_noDelivery() throws Exception {
        // D-102 §6 시나리오 3(primitive 예외 전파·4xx) 실현. Claim.attachExchangeDelivery는 type==EXCHANGE만 검증하고
        // status는 무검증이라 REQUESTED+EXCHANGE는 200을 반환한다(status로는 4xx를 유발할 수 없음). 따라서 status가 아닌
        // type 불일치(RETURN)로 primitive 예외를 유발한다(SellerDeliveryIntegrationTest T3 1:1·"Claim 상태 미승인 등" 흡수).
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.RETURN_REQUESTED);
            seedApprovedClaim(ClaimType.RETURN);
        });

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .headers(authHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        // Delivery.create·save 후 Claim.attachExchangeDelivery type 검증 throw → 단일 트랜잭션 롤백으로 행 미잔존.
        assertThat(deliveryCount()).isZero();
        assertThat(events.stream(DeliveryStarted.class).count()).isZero();
    }

    @Test
    @DisplayName("T4 인증 실패: mark-delivered X-Admin-Id 헤더 부재 → 401 UNAUTHENTICATED·DeliveryCompleted 0")
    void markDelivered_missingAdminHeader_returns401() throws Exception {
        // resolve()가 delivery 조회 이전 최선두에서 throw하므로 시드 불요(deliveryPublicId 미존재여도 401이 우선한다).
        mockMvc.perform(post("/api/v1/admin/deliveries/" + DELIVERY_PID + "/mark-delivered"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(events.stream(DeliveryCompleted.class).count()).isZero();
    }

    @Test
    @DisplayName("T5 성공: 유효 X-Admin-Id + SHIPPING 교환 배송 → 200·DELIVERED 커밋·OrderItem EXCHANGED·Claim COMPLETED·DeliveryCompleted 1회")
    void markDelivered_validAdmin_shippingExchangeDelivery_returns200_completesChain() throws Exception {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.EXCHANGE_REQUESTED);
            seedApprovedClaim(ClaimType.EXCHANGE);
            seedShippingExchangeDelivery();
        });

        // mark-delivered는 body 없음(D-104 확정 스펙). X-Admin-Id 헤더만 전달한다.
        mockMvc.perform(post("/api/v1/admin/deliveries/" + DELIVERY_PID + "/mark-delivered")
                        .headers(authHeaders.admin(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryPublicId").value(DELIVERY_PID))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.carrier").value("CJ"))
                .andExpect(jsonPath("$.trackingNo").value(TRACKING_NO));

        // 동기 응답: Delivery SHIPPING → DELIVERED 커밋(재조회로 stale 회피 검증)
        assertThat(deliveryStatus()).isEqualTo("DELIVERED");
        // AFTER_COMMIT 체인(ExchangeDeliveryCompletedHandler): claim_id SET → OrderItem EXCHANGED·Claim COMPLETED
        assertThat(orderItemStatus()).isEqualTo("EXCHANGED");
        assertThat(claimStatus()).isEqualTo("COMPLETED");
        // E5 DeliveryCompleted 발행 1회
        assertThat(events.stream(DeliveryCompleted.class).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("T6 실패: 미존재 deliveryPublicId → 404 DELIVERY_NOT_FOUND·DeliveryCompleted 0")
    void markDelivered_unknownDeliveryPublicId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deliveries/" + pid("dlv_", "ADNONE") + "/mark-delivered")
                        .headers(authHeaders.admin(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));

        assertThat(events.stream(DeliveryCompleted.class).count()).isZero();
    }

    // ---------- seed·helpers (SellerDeliveryIntegrationTest 패턴 1:1·cross-tenant 시나리오 제외) ----------

    private void seed(Runnable seedingWork) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedingWork.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "ADUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "ADSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "ADPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCAD', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "ADVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedOrder(String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, pid("ord_", "ADORD"), USER_ID, "ORDAD" + ORDER_ID, status, ITEM_PRICE);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                ITEM_PRICE, ITEM_PRICE, itemStatus.name());
    }

    /** APPROVED·picked_up_at 설정 클레임 시드(type 파라미터화·SellerDeliveryIntegrationTest seedApprovedClaim 1:1). */
    private void seedApprovedClaim(ClaimType type) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, "
                        + "previous_order_item_status, picked_up_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PRODUCT_DEFECT', 'APPROVED', 'DELIVERED', NOW(6), NOW(6), NOW(6))",
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, type.name());
    }

    /** SHIPPING 교환 배송 시드(claim_id SET·shipped_at NOW(6)·DLV-3 정합·markDelivered 진입 상태·ClaimExchangeIntegrationTest 패턴 1:1). */
    private void seedShippingExchangeDelivery() {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, tracking_no, status, "
                        + "shipped_at, claim_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CJ', ?, 'SHIPPING', NOW(6), ?, NOW(6), NOW(6))",
                DELIVERY_ID, DELIVERY_PID, ORDER_ITEM_ID, TRACKING_NO, CLAIM_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id = ?)",
                        ORDER_ITEM_ID);
                jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
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

    private String deliveryCarrier() {
        return jdbc.queryForObject("SELECT carrier FROM delivery WHERE order_item_id = ?", String.class, ORDER_ITEM_ID);
    }

    private String deliveryTrackingNo() {
        return jdbc.queryForObject(
                "SELECT tracking_no FROM delivery WHERE order_item_id = ?", String.class, ORDER_ITEM_ID);
    }

    private String orderItemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private String claimStatus() {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, CLAIM_ID);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

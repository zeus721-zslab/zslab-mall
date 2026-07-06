package com.zslab.mall.delivery.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.delivery.event.DeliveryStarted;
import com.zslab.mall.order.enums.OrderItemStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * Seller 교환품 출고 등록 endpoint E2E 통합 테스트(Track 15·D-99·실 MariaDB). HTTP → {@code SellerDeliveryController} →
 * {@code ClaimService.registerExchangeShipmentBySeller} → {@code DeliveryService.registerExchangeShipment} → DB 흐름의
 * 권한 검증(Q9·Q10)·Aggregate 불변식(D-98 Q13)·Q11 이중 호출 멱등 가드를 실 커밋·HTTP 경유로 실측한다(라이브 트랩 차단).
 *
 * <p><b>트랜잭션</b>: DeliveryStarted 동기 소비·AFTER_COMMIT 알림 핸들러를 실제 커밋으로 구동하므로 클래스에 {@code @Transactional}을
 * 두지 않는다(ClaimReturnIntegrationTest·ClaimExchangeIntegrationTest 패턴 1:1). 시드/정리는 {@link TransactionTemplate} +
 * {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), 검증은 {@link JdbcTemplate} 직접 조회·이벤트는 {@link ApplicationEvents}로 한다.
 *
 * <p><b>HTTP 경유 의무(D-99)</b>: {@code DeliveryService.registerExchangeShipment} primitive를 직접 호출하지 않고 MockMvc로
 * endpoint를 구동한다. X-Seller-Id 헤더 stub은 {@code HeaderSellerActorResolver}가 BIGINT로 해소한다(D-93).
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
class SellerDeliveryIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9415L;
    private static final long SELLER_A = 9415L; // 품목 소유 셀러
    private static final long SELLER_B = 9416L; // 타 셀러(cross-tenant)
    // Track 36 γ Phase 3: actorId(JWT subject)를 seller_id와 다른 값으로 둔다 — user.id==seller.id 우연일치 은폐 제거.
    private static final long SELLER_A_USER = 9417L; // SELLER_A 소속 user(actorId)
    private static final long SELLER_B_USER = 9418L; // SELLER_B 소속 user(cross-tenant actorId)
    private static final long PRODUCT_ID = 9415L;
    private static final long VARIANT_ID = 9415L;
    private static final long ORDER_ID = 9415L;
    private static final long ORDER_ITEM_ID = 9415L;
    private static final long CLAIM_ID = 9415L;
    private static final long DUMMY_FK_ID = 9415L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String CLAIM_PID = pid("clm_", "SDCLM");
    private static final String ORDER_ITEM_PID = pid("oit_", "SDOIT");
    private static final String TRACKING_NO = "CJ-SD-0001";

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
    @DisplayName("T1 정상 등록: 소유 셀러 → 200·SHIPPING Delivery 커밋·claim_id 연결·DeliveryStarted 1회")
    void register_ownerSeller_returns200_persistsShippingDelivery() throws Exception {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.EXCHANGE_REQUESTED);
            seedApprovedClaim(ClaimType.EXCHANGE);
        });

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .headers(authHeaders.seller(SELLER_A_USER))
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
    @DisplayName("T2 cross-tenant: 타 셀러 → 404 CLAIM_NOT_FOUND·Delivery 미생성·이벤트 0(정보 노출 회피·D-99 Q9·Q10)")
    void register_crossTenant_returns404_noDelivery() throws Exception {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.EXCHANGE_REQUESTED);
            seedApprovedClaim(ClaimType.EXCHANGE);
        });

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .headers(authHeaders.seller(SELLER_B_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));

        assertThat(deliveryCount()).isZero();
        assertThat(events.stream(DeliveryStarted.class).count()).isZero();
    }

    @Test
    @DisplayName("T3 type 불일치: RETURN 클레임 → 422 CLAIM_STATE_INVALID·Delivery 롤백(D-98 Q13·D-99 Q10)")
    void register_returnTypeClaim_returns422_noDelivery() throws Exception {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.RETURN_REQUESTED);
            seedApprovedClaim(ClaimType.RETURN);
        });

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        // Delivery.create·save 후 Claim.attachExchangeDelivery type 검증 throw → 단일 트랜잭션 롤백으로 행 미잔존.
        assertThat(deliveryCount()).isZero();
        assertThat(events.stream(DeliveryStarted.class).count()).isZero();
    }

    @Test
    @DisplayName("T4 Q11 멱등 회귀: 동일 claimId 재호출 → 422·Delivery 추가 생성 없음·기존 행 불변·DeliveryStarted 1회")
    void register_duplicateCall_returns422_noAdditionalDelivery() throws Exception {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.EXCHANGE_REQUESTED);
            seedApprovedClaim(ClaimType.EXCHANGE);
        });

        // 1차: 정상 등록(Delivery claim_id 연결 커밋)
        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isOk());

        // 2차: 동일 claimId·다른 carrier/trackingNo 재호출 → Q11 가드 throw
        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .headers(authHeaders.seller(SELLER_A_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("HANJIN", "HJ-SD-9999")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(deliveryCarrier()).isEqualTo("CJ");
        assertThat(deliveryTrackingNo()).isEqualTo(TRACKING_NO);
        assertThat(events.stream(DeliveryStarted.class).count()).isEqualTo(1L);
    }

    // ---------- seed·helpers (ClaimExchangeIntegrationTest 패턴 1:1·claim 시드만 type 파라미터화) ----------

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
                USER_ID, pid("usr_", "SDUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_A, pid("slr_", "SDSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "SDPRD"), SELLER_A, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCSD', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "SDVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedOrder(String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, pid("ord_", "SDORD"), USER_ID, "ORDSD" + ORDER_ID, status, ITEM_PRICE);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_A,
                ITEM_PRICE, ITEM_PRICE, itemStatus.name());
    }

    /** APPROVED·picked_up_at 설정 클레임 시드(type 파라미터화·ClaimExchangeIntegrationTest seedApprovedReturnClaim 1:1 + picked_up_at). */
    private void seedApprovedClaim(ClaimType type) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, "
                        + "previous_order_item_status, picked_up_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PRODUCT_DEFECT', 'APPROVED', 'DELIVERED', NOW(6), NOW(6), NOW(6))",
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, type.name());
    }

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

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", SELLER_A_USER, SELLER_B_USER);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id = ?)",
                        ORDER_ITEM_ID);
                jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
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

    private String deliveryCarrier() {
        return jdbc.queryForObject("SELECT carrier FROM delivery WHERE order_item_id = ?", String.class, ORDER_ITEM_ID);
    }

    private String deliveryTrackingNo() {
        return jdbc.queryForObject(
                "SELECT tracking_no FROM delivery WHERE order_item_id = ?", String.class, ORDER_ITEM_ID);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

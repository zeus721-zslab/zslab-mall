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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * 셀러 교환품 출고 endpoint 제거 반전 통합 테스트(Track 92·D-196·실 MariaDB). "셀러는 조회만·처리는 관리자"가 확정이므로
 * {@code POST /api/v1/claims/{id}/register-exchange-shipment}는 매핑이 없어야 하며, 경로 역할 쿠키(구매자)로 호출하면
 * 404 RESOURCE_NOT_FOUND다. endpoint가 되살아나면 404가 아닌 응답으로 RED가 난다. 셀러 쿠키는 {@code /api/v1/claims/**}에서
 * 읽히지 않아(D-235 PR3) 엔드포인트 유무와 무관하게 401이므로 부재 판정에 쓰지 않는다(false-green). 관리자 정상 경로는
 * AdminDeliveryControllerIntegrationTest 책임이다.
 *
 * <p><b>트랜잭션</b>: 클래스에 {@code @Transactional}을 두지 않고 시드/정리는 {@link TransactionTemplate} +
 * {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), 검증은 {@link JdbcTemplate} 직접 조회·이벤트는 {@link ApplicationEvents}로 한다
 * (ClaimReturnIntegrationTest·ClaimExchangeIntegrationTest 패턴 1:1).
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
class SellerDeliveryIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9415L;
    private static final long SELLER_A = 9415L; // 품목 소유 셀러
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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ===== R5: 셀러 교환품 출고 endpoint 제거 반전(Track 92) — endpoint가 되살아나면 404가 아니게 되어 RED =====

    @Test
    @DisplayName("R5 교환품 출고: 구매자 쿠키 → 404 RESOURCE_NOT_FOUND·Delivery 미생성·DeliveryStarted 0건(Track 92 셀러 처리 endpoint 제거)")
    void register_buyerCookie_returns404_noDelivery() throws Exception {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.EXCHANGE_REQUESTED);
            seedApprovedClaim(ClaimType.EXCHANGE);
        });

        mockMvc.perform(post("/api/v1/claims/" + CLAIM_PID + "/register-exchange-shipment")
                        .with(authHeaders.buyer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CJ", TRACKING_NO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(deliveryCount()).isZero();
        assertThat(events.stream(DeliveryStarted.class).count()).isZero();
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
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '테스트 상품', 1000)",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_A,
                ITEM_PRICE, ITEM_PRICE, itemStatus.name());
    }

    /** APPROVED·picked_up_at 설정 클레임 시드(type 파라미터화·ClaimExchangeIntegrationTest seedApprovedReturnClaim 1:1 + picked_up_at). */
    private void seedApprovedClaim(ClaimType type) {
        // Track 83 D-177: 교환품 발송 가드(검수 PASS)·종결(예약 확정·옵션 갱신) 전제를 EXCHANGE 시드에 함께 채운다(같은 variant·예약 1).
        boolean exchange = type == ClaimType.EXCHANGE;
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, "
                        + "previous_order_item_status, picked_up_at, inspected_at, inspection_result, restock, "
                        + "exchange_variant_id, original_variant_id, exchange_reserved_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PRODUCT_DEFECT', 'APPROVED', 'DELIVERED', NOW(6), ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, type.name(),
                exchange ? java.time.LocalDateTime.now() : null, exchange ? "PASS" : null, exchange ? 1 : null,
                exchange ? VARIANT_ID : null, exchange ? VARIANT_ID : null, exchange ? java.time.LocalDateTime.now() : null);
        if (exchange) {
            jdbc.update("UPDATE inventory SET quantity_reserved = quantity_reserved + 1, quantity_available = quantity_available - 1 "
                    + "WHERE variant_id = ?", VARIANT_ID);
        }
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

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

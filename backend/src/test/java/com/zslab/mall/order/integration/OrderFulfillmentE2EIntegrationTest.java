package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
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
 * 주문 이행 E2E 관통 통합 테스트(Track 43·M2·실 MariaDB·Flyway). 결제완료→발송→배송완료를 실 HTTP 4단계 연쇄로 관통 검증한다.
 * 세그먼트별 통합 테스트가 앞 단계를 JDBC 시드로 대체하던 공백(RECON B5·B6)을 처음으로 실 HTTP 연쇄로 메운다.
 *
 * <p><b>단계(모두 실 HTTP·MockMvc)</b>:
 * <ol>
 *   <li>① {@code POST /api/v1/orders}(buyer) → 201·Location에서 orderPublicId 파싱·Payment PENDING 생성</li>
 *   <li>② {@code POST /api/webhooks/payments}(SUCCESS·무인증 webhook) → Payment/OrderItem/Order PAID(E2)</li>
 *   <li>③ {@code POST /api/v1/order-items/{oit}/prepare-shipment}(seller) → Delivery SHIPPING·OrderItem SHIPPING(E4)</li>
 *   <li>④ {@code POST /api/v1/deliveries/{dlv}/mark-delivered}(seller·Track 43 신설) → Delivery DELIVERED·OrderItem/Order DELIVERED(E5)</li>
 * </ol>
 *
 * <p><b>회원가입·상품등록 = 전제 시드</b>(RECON B6): 실 HTTP 관통의 핵심인 결제→배송 트리거 4단계에 집중하기 위해 buyer(user)·seller·
 * seller_user·product·variant·inventory는 JDBC로 시드한다. 단계 간 연결은 응답 public_id 파싱 연쇄(order→payment attempt_key→orderItem→delivery)로
 * 잇는다. 상품 등록 seller ≡ prepare-shipment/mark-delivered seller가 되도록 seller_user 매핑을 시드한다(order_item.seller_id = product.seller_id).
 *
 * <p><b>트랜잭션</b>: 각 HTTP 호출이 독립 커밋되고 AFTER_COMMIT 핸들러(재고 확정·알림)를 실 구동하므로 클래스에 {@code @Transactional}을 두지 않는다.
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), 검증은 {@link JdbcTemplate} 직접 조회로 한다.
 * 앱이 write하는 모든 행의 FK(order→user·order_item→product/variant/seller·payment→order·delivery→order_item)는 시드된 행으로 해소되므로
 * 앱 커넥션 FK 상태와 무관하게 정합하다(orphan은 product.category_id·variant.option1_value_id뿐이며 재-write 대상이 아님).
 */
@AutoConfigureMockMvc
class OrderFulfillmentE2EIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 9450L; // JWT subject(BUYER)·order.buyer_id
    private static final long SELLER_ID = 9451L; // 품목 소유 셀러
    private static final long SELLER_USER = 9452L; // JWT subject(SELLER)·seller_user 매핑 → SELLER_ID
    private static final long PRODUCT_ID = 9453L;
    private static final long VARIANT_ID = 9454L;
    private static final long INVENTORY_ID = 9455L;
    private static final long DUMMY_FK_ID = 9456L; // category_id·option1_value_id orphan(FK_CHECKS=0 시드·재-write 없음)
    private static final long BASE_PRICE = 8_000L;
    private static final long ADDITIONAL_PRICE = 2_000L;
    private static final long QUANTITY = 2L;
    private static final long EXPECTED_AMOUNT = (BASE_PRICE + ADDITIONAL_PRICE) * QUANTITY; // 20000

    private static final String SELLER_PID = pid("slr_", "E2ESLR");
    private static final String PRODUCT_PID = pid("prd_", "E2EPRD");
    private static final String VARIANT_PID = pid("var_", "E2EVAR");
    private static final String TRACKING_NO = "CJ-E2E-0001";
    private static final String PG_TID = "tid_track43_e2e_0001";

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
        seedPrerequisites();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("E2E 관통: 주문→결제완료(webhook)→발송(seller)→배송완료(seller) 실 HTTP 4연쇄 → Payment PAID·Order/OrderItem/Delivery DELIVERED")
    void fulfillment_orderToDelivered_endToEnd() throws Exception {
        // ① 주문 생성(buyer) → 201·Location에서 orderPublicId 파싱
        String location = mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(createOrderBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        String orderPublicId = location.substring(location.lastIndexOf('/') + 1);

        assertThat(orderStatus(orderPublicId)).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatus(orderPublicId)).isEqualTo("PENDING");

        // ② 결제완료 webhook(SUCCESS·무인증) → Payment/OrderItem/Order PAID
        String attemptKey = paymentAttemptKey(orderPublicId);
        mockMvc.perform(post("/api/webhooks/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(webhookBody(attemptKey)))
                .andExpect(status().isOk());

        assertThat(paymentStatus(orderPublicId)).isEqualTo("PAID");
        assertThat(orderItemStatus(orderPublicId)).isEqualTo("PAID");
        assertThat(orderStatus(orderPublicId)).isEqualTo("PAID");

        // ③ 발송(seller) → Delivery SHIPPING·OrderItem SHIPPING
        String orderItemPublicId = orderItemPublicId(orderPublicId);
        mockMvc.perform(post("/api/v1/order-items/" + orderItemPublicId + "/prepare-shipment")
                        .headers(authHeaders.seller(SELLER_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"CJ\",\"trackingNo\":\"" + TRACKING_NO + "\"}"))
                .andExpect(status().isOk());

        assertThat(orderItemStatus(orderPublicId)).isEqualTo("SHIPPING");
        assertThat(orderStatus(orderPublicId)).isEqualTo("SHIPPING");
        assertThat(deliveryStatus(orderPublicId)).isEqualTo("SHIPPING");

        // ④ 배송완료(seller·Track 43 신설) → Delivery DELIVERED·OrderItem/Order DELIVERED
        String deliveryPublicId = deliveryPublicId(orderPublicId);
        mockMvc.perform(post("/api/v1/deliveries/" + deliveryPublicId + "/mark-delivered")
                        .headers(authHeaders.seller(SELLER_USER)))
                .andExpect(status().isOk());

        assertThat(deliveryStatus(orderPublicId)).isEqualTo("DELIVERED");
        assertThat(orderItemStatus(orderPublicId)).isEqualTo("DELIVERED");
        assertThat(orderStatus(orderPublicId)).isEqualTo("DELIVERED");
        // 결제완료 이후 금전 상태는 관통 종료까지 PAID 불변
        assertThat(paymentStatus(orderPublicId)).isEqualTo("PAID");
    }

    // ---------- 요청 본문 ----------

    private String createOrderBody() {
        // 서버 가격 산정: (base_price 8000 + additional_price 2000) * quantity 2 = 20000(EXPECTED_AMOUNT).
        return "{"
                + "\"items\": [ { \"productId\": \"" + PRODUCT_PID + "\", \"variantId\": \"" + VARIANT_PID
                + "\", \"quantity\": " + QUANTITY + " } ],"
                + "\"shippingAddress\": {"
                + "\"recipientName\": \"홍길동\", \"recipientPhone\": \"010-1234-5678\","
                + "\"zonecode\": \"06236\", \"addressRoad\": \"서울 강남대로 1\", \"addressDetail\": \"101호\" },"
                + "\"method\": \"CARD\""
                + "}";
    }

    private String webhookBody(String attemptKey) {
        return "{"
                + "\"provider\": \"MOCK_PG\","
                + "\"callbackType\": \"SUCCESS\","
                + "\"paymentAttemptKey\": \"" + attemptKey + "\","
                + "\"pgTid\": \"" + PG_TID + "\","
                + "\"occurredAt\": \"2026-07-05T00:00:00\""
                + "}";
    }

    // ---------- seed·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedPrerequisites() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "E2EUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙43샵', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, SELLER_PID);
                // resolver 해소용 seller_user 실 매핑(actorId≠seller_id·role_id=SELLER_OWNER seed).
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        SELLER_USER, SELLER_ID);
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙43상품', 'SALE', ?, NOW(6), NOW(6))",
                        PRODUCT_ID, PRODUCT_PID, SELLER_ID, DUMMY_FK_ID, BASE_PRICE);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCE2E', ?, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, VARIANT_PID, PRODUCT_ID, ADDITIONAL_PRICE, DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, "
                                + "quantity_available, created_at, updated_at) VALUES (?, ?, 100, 0, 100, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID);
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
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id IN "
                        + "(SELECT id FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?))", BUYER_ID);
                jdbc.update("DELETE FROM payment WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER_ID);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER_ID);
                jdbc.update("DELETE FROM order_idempotency_key WHERE buyer_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", SELLER_USER);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String orderStatus(String orderPublicId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE public_id = ?", String.class, orderPublicId);
    }

    private String paymentStatus(String orderPublicId) {
        return jdbc.queryForObject("SELECT p.status FROM payment p JOIN `order` o ON o.id = p.order_id "
                + "WHERE o.public_id = ?", String.class, orderPublicId);
    }

    private String paymentAttemptKey(String orderPublicId) {
        return jdbc.queryForObject("SELECT p.payment_attempt_key FROM payment p JOIN `order` o ON o.id = p.order_id "
                + "WHERE o.public_id = ?", String.class, orderPublicId);
    }

    private String orderItemStatus(String orderPublicId) {
        return jdbc.queryForObject("SELECT oi.item_status FROM order_item oi JOIN `order` o ON o.id = oi.order_id "
                + "WHERE o.public_id = ?", String.class, orderPublicId);
    }

    private String orderItemPublicId(String orderPublicId) {
        return jdbc.queryForObject("SELECT oi.public_id FROM order_item oi JOIN `order` o ON o.id = oi.order_id "
                + "WHERE o.public_id = ?", String.class, orderPublicId);
    }

    private String deliveryStatus(String orderPublicId) {
        return jdbc.queryForObject("SELECT d.status FROM delivery d JOIN order_item oi ON oi.id = d.order_item_id "
                + "JOIN `order` o ON o.id = oi.order_id WHERE o.public_id = ?", String.class, orderPublicId);
    }

    private String deliveryPublicId(String orderPublicId) {
        return jdbc.queryForObject("SELECT d.public_id FROM delivery d JOIN order_item oi ON oi.id = d.order_item_id "
                + "JOIN `order` o ON o.id = oi.order_id WHERE o.public_id = ?", String.class, orderPublicId);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

package com.zslab.mall.order.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 주문 상세 품목 배송 정보(Track 96-2 D-203·C-05) 통합 테스트(실 MariaDB·HTTP 경유). {@code OrderItemResponse.delivery}가
 * 원 발송(OUTBOUND·claim_id NULL) 최신 1건만 담고, 교환품 발송(claim_id 연결)·반품 회수(RETURN)·구 발송은 제외되는지,
 * 미발송 품목은 null(NON_NULL로 생략)인지, 타인 주문 404가 유지되는지 검증한다.
 *
 * <p><b>커버</b>: T1 원 발송만(교환 OUTBOUND·RETURN·구 발송 제외·최신 원 발송) · T2 부분 발송(품목별·미발송 null) · T3 타 buyer 404.
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·id 9620~9629 고정.
 */
@AutoConfigureMockMvc
class BuyerOrderDeliveryQueryIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_USER = 9620L;
    private static final long OTHER_BUYER = 9621L;
    private static final long SELLER_ID = 9620L;
    private static final long PRODUCT_ID = 9620L;
    private static final long VARIANT_ID = 9620L;
    private static final long ORDER_ID = 9620L;
    private static final long SHIPPED_ITEM_ID = 9620L;   // 원 발송 2건 + 교환 발송 + 회수
    private static final long UNSHIPPED_ITEM_ID = 9621L; // 발송 0건
    private static final long CLAIM_ID = 9620L;
    private static final long DELIVERY_OLD_ORIGINAL = 9620L;
    private static final long DELIVERY_NEW_ORIGINAL = 9621L;
    private static final long DELIVERY_EXCHANGE = 9622L;
    private static final long DELIVERY_RETURN = 9623L;
    private static final long DUMMY_FK_ID = 9620L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String ORDER_PID = pid("ord_", "BODQORD");
    private static final String SHIPPED_ITEM_PID = pid("oit_", "BODQIT1");
    private static final String UNSHIPPED_ITEM_PID = pid("oit_", "BODQIT2");
    private static final String ORDER_URL = "/api/v1/orders/" + ORDER_PID;
    private static final String SHIPPED_ITEM_PATH = "$.sellers[0].items[?(@.orderItemId == '" + SHIPPED_ITEM_PID + "')]";
    private static final String UNSHIPPED_ITEM_PATH = "$.sellers[0].items[?(@.orderItemId == '" + UNSHIPPED_ITEM_PID + "')]";

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
        seedGraph();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 원 발송만: 교환 OUTBOUND(claim 연결·최신 id)·RETURN·구 원 발송 제외 → 최신 원 발송(택배사·송장·상태·발송일·배송완료일)")
    void detail_originalOutboundOnly() throws Exception {
        mockMvc.perform(get(ORDER_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SHIPPED_ITEM_PATH + ".delivery.carrier").value("HANJIN"))
                .andExpect(jsonPath(SHIPPED_ITEM_PATH + ".delivery.trackingNo").value("ORIG-NEW"))
                .andExpect(jsonPath(SHIPPED_ITEM_PATH + ".delivery.status").value("DELIVERED"))
                .andExpect(jsonPath(SHIPPED_ITEM_PATH + ".delivery.shippedAt").value("2026-09-10T09:00:00+09:00"))
                .andExpect(jsonPath(SHIPPED_ITEM_PATH + ".delivery.deliveredAt").value("2026-09-12T15:30:00+09:00"));
    }

    @Test
    @DisplayName("T2 부분 발송: 미발송 품목은 delivery 부재(null·NON_NULL 생략)·발송 품목만 채움")
    void detail_partialShipment() throws Exception {
        mockMvc.perform(get(ORDER_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].items.length()").value(2))
                .andExpect(jsonPath(UNSHIPPED_ITEM_PATH + ".delivery").doesNotExist())
                .andExpect(jsonPath(SHIPPED_ITEM_PATH + ".delivery.trackingNo").value("ORIG-NEW"));
    }

    @Test
    @DisplayName("T3 cross-tenant: 타 buyer → 404 ORDER_NOT_FOUND(존재 은닉·배송 정보 미노출)")
    void detail_crossTenant_returns404() throws Exception {
        mockMvc.perform(get(ORDER_URL).headers(authHeaders.buyer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    // ---------- seed·helpers (BuyerOrderConfirmControllerIntegrationTest 패턴) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * 품목 2개 주문. SHIPPED_ITEM에는 원 발송 구(CJ·ORIG-OLD·id 작음) → 원 발송 신(HANJIN·ORIG-NEW·DELIVERED) → 교환 발송(POST·EXCH·claim 연결·
     * id 최대) → 반품 회수(RETURN·LOGEN) 순으로 시드해 "OUTBOUND ∧ claim_id NULL 중 최신"만 선택되는지 본다. UNSHIPPED_ITEM은 발송 0건.
     */
    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_USER, pid("usr_", "BODQUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙96셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BODQSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙96상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "BODQPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCBODQ', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "BODQVAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'SHIPPING', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, ORDER_PID, BUYER_USER, "ORDBODQ" + ORDER_ID, ITEM_PRICE * 2);
                insertItem(SHIPPED_ITEM_ID, SHIPPED_ITEM_PID, "DELIVERED");
                insertItem(UNSHIPPED_ITEM_ID, UNSHIPPED_ITEM_PID, "PAID");
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                                + "previous_order_item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'EXCHANGE', 'PRODUCT_DEFECT', 'COMPLETED', ?, 'DELIVERED', NOW(6), NOW(6))",
                        CLAIM_ID, pid("clm_", "BODQCLM"), SHIPPED_ITEM_ID, BUYER_USER);
                insertDelivery(DELIVERY_OLD_ORIGINAL, "BODQDLV1", "OUTBOUND", "CJ", "ORIG-OLD", "DELIVERED",
                        "2026-09-01 09:00:00", "2026-09-03 10:00:00", null);
                insertDelivery(DELIVERY_NEW_ORIGINAL, "BODQDLV2", "OUTBOUND", "HANJIN", "ORIG-NEW", "DELIVERED",
                        "2026-09-10 09:00:00", "2026-09-12 15:30:00", null);
                insertDelivery(DELIVERY_EXCHANGE, "BODQDLV3", "OUTBOUND", "POST", "EXCH-1", "SHIPPING",
                        "2026-09-15 09:00:00", null, CLAIM_ID);
                insertDelivery(DELIVERY_RETURN, "BODQDLV4", "RETURN", "LOGEN", "RTN-1", "SHIPPING",
                        "2026-09-14 09:00:00", null, CLAIM_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertItem(long id, String publicId, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '테스트 상품', 1000)",
                id, publicId, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus);
    }

    private void insertDelivery(long id, String tag, String direction, String carrier, String trackingNo, String deliveryStatus,
            String shippedAt, String deliveredAt, Long claimId) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                        + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("dlv_", tag), SHIPPED_ITEM_ID, direction, carrier, trackingNo, deliveryStatus, shippedAt, deliveredAt, claimId);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM delivery WHERE id BETWEEN ? AND ?", DELIVERY_OLD_ORIGINAL, DELIVERY_RETURN);
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM order_item WHERE id IN (?, ?)", SHIPPED_ITEM_ID, UNSHIPPED_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", BUYER_USER, OTHER_BUYER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

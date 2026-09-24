package com.zslab.mall.order.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
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
 * 구매자 주문 현황 요약 {@code GET /api/v1/orders/summary}(Track 105-2d·D-223) 통합 테스트(실 MariaDB·HTTP 경유).
 *
 * <p><b>커버</b>: T1 5단계 건수(ORDERED·클레임 계열 제외·3개월 경계 안 포함·경계 밖 제외·타 구매자 제외) · T2 activeClaimCount
 * (REQUESTED·APPROVED만·기간 제한 없음·타 구매자 제외) · T3 주문 없음 → 전부 0 · T4 비로그인 401 · T5 비BUYER 403 ·
 * T6 /summary가 주문 상세(/{orderPublicId})로 해석되지 않음.
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·id 985000~985029 고정.
 * 주문일은 테스트 JVM 시각 기준으로 넣는다 — 서버도 같은 JVM의 {@code LocalDateTime.now()}로 기간 하한을 계산한다.
 */
@AutoConfigureMockMvc
class BuyerOrderStatusSummaryIntegrationTest extends AbstractIntegrationTest {

    private static final String SUMMARY_URL = "/api/v1/orders/summary";

    private static final long ID_BASE = 985000L;
    private static final long ID_LAST = 985029L;
    private static final long BUYER_USER = ID_BASE;
    private static final long OTHER_BUYER = ID_BASE + 1;
    private static final long EMPTY_BUYER = ID_BASE + 2;
    private static final long SELLER_ID = ID_BASE;
    private static final long PRODUCT_ID = ID_BASE;
    private static final long VARIANT_ID = ID_BASE;
    private static final long DUMMY_FK_ID = ID_BASE;
    private static final long ORDER_RECENT = ID_BASE;
    private static final long ORDER_INSIDE_BOUNDARY = ID_BASE + 1;
    private static final long ORDER_OUTSIDE_BOUNDARY = ID_BASE + 2;
    private static final long ORDER_OTHER_BUYER = ID_BASE + 3;
    private static final long SUMMARY_PERIOD_MONTHS = 3L;
    private static final long BOUNDARY_MARGIN_HOURS = 1L;
    private static final long ITEM_PRICE = 10_000L;

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
    @DisplayName("T1 5단계 건수: 품목 상태 1:1·ORDERED/클레임 계열 제외·3개월 경계 안 포함/밖 제외·타 구매자 제외")
    void summary_stageCounts() throws Exception {
        mockMvc.perform(get(SUMMARY_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodMonths").value(3))
                .andExpect(jsonPath("$.stages.paid").value(2))
                .andExpect(jsonPath("$.stages.preparing").value(1))
                .andExpect(jsonPath("$.stages.shipping").value(1))
                .andExpect(jsonPath("$.stages.delivered").value(2))   // 최근 1 + 경계 안 1
                .andExpect(jsonPath("$.stages.confirmed").value(1));  // 경계 밖 CONFIRMED 제외
    }

    @Test
    @DisplayName("T2 activeClaimCount: REQUESTED·APPROVED만 합계(REJECTED·COMPLETED 제외)·경계 밖 주문 클레임 포함·타 구매자 제외")
    void summary_activeClaimCount() throws Exception {
        mockMvc.perform(get(SUMMARY_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeClaimCount").value(3));
    }

    @Test
    @DisplayName("T3 주문 없음: 5단계 모두 0·activeClaimCount 0(0건 단계도 응답)")
    void summary_noOrders_allZero() throws Exception {
        mockMvc.perform(get(SUMMARY_URL).headers(authHeaders.buyer(EMPTY_BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodMonths").value(3))
                .andExpect(jsonPath("$.stages.paid").value(0))
                .andExpect(jsonPath("$.stages.preparing").value(0))
                .andExpect(jsonPath("$.stages.shipping").value(0))
                .andExpect(jsonPath("$.stages.delivered").value(0))
                .andExpect(jsonPath("$.stages.confirmed").value(0))
                .andExpect(jsonPath("$.activeClaimCount").value(0));
    }

    @Test
    @DisplayName("T4 비로그인 → 401 UNAUTHENTICATED")
    void summary_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(SUMMARY_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("T5 비BUYER(SELLER) → 403 FORBIDDEN")
    void summary_seller_returns403() throws Exception {
        mockMvc.perform(get(SUMMARY_URL).headers(authHeaders.seller(SELLER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("T6 /summary는 주문 상세로 해석되지 않음(상세였다면 404 ORDER_NOT_FOUND·sellers 필드)")
    void summary_notResolvedAsOrderDetail() throws Exception {
        mockMvc.perform(get(SUMMARY_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages").exists())
                .andExpect(jsonPath("$.sellers").doesNotExist())
                .andExpect(jsonPath("$.orderId").doesNotExist());
    }

    // ---------- seed·helpers (BuyerOrderDeliveryQueryIntegrationTest 패턴) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * BUYER: 최근 주문(12상태 품목 각 1 + PAID 1 추가) · 경계 안 주문(3개월 − 1시간 전·DELIVERED) · 경계 밖 주문(3개월 + 1시간 전·CONFIRMED).
     * OTHER_BUYER: 최근 주문(PAID). 클레임: 최근 주문 품목에 REQUESTED·APPROVED·REJECTED·COMPLETED 각 1 · 경계 밖 품목에
     * APPROVED 1 · 타 구매자 품목에 REQUESTED 1.
     */
    private void seedGraph() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodStart = now.minusMonths(SUMMARY_PERIOD_MONTHS);
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                insertUser(BUYER_USER, "BOSSUSR1");
                insertUser(OTHER_BUYER, "BOSSUSR2");
                insertUser(EMPTY_BUYER, "BOSSUSR3");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '요약셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BOSSSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '요약상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "BOSSPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCBOSS', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "BOSSVAR"), PRODUCT_ID, DUMMY_FK_ID);

                insertOrder(ORDER_RECENT, "BOSSORD1", BUYER_USER, now.minusDays(1));
                insertOrder(ORDER_INSIDE_BOUNDARY, "BOSSORD2", BUYER_USER, periodStart.plusHours(BOUNDARY_MARGIN_HOURS));
                insertOrder(ORDER_OUTSIDE_BOUNDARY, "BOSSORD3", BUYER_USER, periodStart.minusHours(BOUNDARY_MARGIN_HOURS));
                insertOrder(ORDER_OTHER_BUYER, "BOSSORD4", OTHER_BUYER, now.minusDays(1));

                String[] recentStatuses = {"ORDERED", "PAID", "PAID", "PREPARING", "SHIPPING", "DELIVERED", "CONFIRMED",
                        "CANCEL_REQUESTED", "CANCELLED", "RETURN_REQUESTED", "RETURNED", "EXCHANGE_REQUESTED", "EXCHANGED"};
                for (int index = 0; index < recentStatuses.length; index++) {
                    insertItem(ID_BASE + index, ORDER_RECENT, recentStatuses[index]);
                }
                long insideItemId = ID_BASE + 20;
                long outsideItemId = ID_BASE + 21;
                long otherBuyerItemId = ID_BASE + 22;
                insertItem(insideItemId, ORDER_INSIDE_BOUNDARY, "DELIVERED");
                insertItem(outsideItemId, ORDER_OUTSIDE_BOUNDARY, "CONFIRMED");
                insertItem(otherBuyerItemId, ORDER_OTHER_BUYER, "PAID");

                insertClaim(ID_BASE, ID_BASE + 7, "REQUESTED", BUYER_USER);
                insertClaim(ID_BASE + 1, ID_BASE + 9, "APPROVED", BUYER_USER);
                insertClaim(ID_BASE + 2, ID_BASE + 8, "REJECTED", BUYER_USER);
                insertClaim(ID_BASE + 3, ID_BASE + 10, "COMPLETED", BUYER_USER);
                insertClaim(ID_BASE + 4, outsideItemId, "APPROVED", BUYER_USER);
                insertClaim(ID_BASE + 5, otherBuyerItemId, "REQUESTED", OTHER_BUYER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertUser(long id, String tag) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", id, pid("usr_", tag));
    }

    private void insertOrder(long id, String tag, long buyerId, LocalDateTime orderedAt) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, ?, NOW(6), NOW(6))",
                id, pid("ord_", tag), buyerId, "ORD" + tag, ITEM_PRICE, orderedAt);
    }

    private void insertItem(long id, long orderId, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '요약상품', 1000)",
                id, pid("oit_", "BOSSIT" + id), orderId, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus);
    }

    private void insertClaim(long id, long orderItemId, String claimStatus, long requestedBy) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                        + "previous_order_item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'RETURN', 'CHANGE_OF_MIND', ?, ?, 'DELIVERED', NOW(6), NOW(6))",
                id, pid("clm_", "BOSSCLM" + id), orderItemId, claimStatus, requestedBy);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

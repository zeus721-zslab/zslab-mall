package com.zslab.mall.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.Locale;
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
 * 구매자 주문 목록 품목 상태 필터 {@code GET /api/v1/orders?itemStatus=}(Track 105-4b·D-224) 통합 테스트(실 MariaDB·HTTP 경유).
 *
 * <p><b>커버</b>: T1 일치 품목 주문만 · T2 한 주문 여러 품목 일치 → 1건 · T3 기간 밖 제외(경계 안 포함) · T4 타 구매자 제외 ·
 * T5 totalCount(count 쿼리가 나가는 페이지 크기) · T6 파라미터 없음 → 기존 결과 · T7 잘못된 값·단계 밖 값 400 ·
 * T8 요약 단계별 품목 수 = 필터 결과의 해당 상태 품목 수 · T9 ADMIN 403(D-223 §8 이월).
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·id 985200~985249 고정.
 * 주문일은 테스트 JVM 시각 기준으로 넣는다 — 서버도 같은 JVM의 {@code LocalDateTime.now()}로 기간 하한을 계산한다.
 */
@AutoConfigureMockMvc
class BuyerOrderItemStatusFilterIntegrationTest extends AbstractIntegrationTest {

    private static final String ORDERS_URL = "/api/v1/orders";
    private static final String SUMMARY_URL = "/api/v1/orders/summary";

    private static final long ID_BASE = 985200L;
    private static final long ID_LAST = 985249L;
    private static final long BUYER_USER = ID_BASE;
    private static final long OTHER_BUYER = ID_BASE + 1;
    private static final long ADMIN_USER = ID_BASE + 2;
    private static final long SELLER_ID = ID_BASE;
    private static final long PRODUCT_ID = ID_BASE;
    private static final long VARIANT_ID = ID_BASE;
    private static final long DUMMY_FK_ID = ID_BASE;
    private static final long SUMMARY_PERIOD_MONTHS = 3L;
    /**
     * 경계 여유(일). 시드와 서버가 각자 now().minusMonths(3)을 계산하는데, 월말 자정을 넘기면 말일 클램프로 서버 하한이 최대 약 1일
     * 앞당겨진다(예: 5/30 23:59 → 2/28 23:59 · 5/31 00:00 → 2/28 00:00). 시간 단위 여유면 경계 밖 주문이 안으로 들어올 수 있다.
     */
    private static final long BOUNDARY_MARGIN_DAYS = 2L;
    private static final long ITEM_PRICE = 10_000L;
    private static final String[] STAGE_STATUSES = {"PAID", "PREPARING", "SHIPPING", "DELIVERED", "CONFIRMED"};

    // 주문 public id(주문일 내림차순: TWO_SHIPPING > ONE_SHIPPING > NO_SHIPPING > EXPIRED > INSIDE > OUTSIDE)
    private static final String ORDER_TWO_SHIPPING = pid("ord_", "BOISF1");
    private static final String ORDER_ONE_SHIPPING = pid("ord_", "BOISF2");
    private static final String ORDER_NO_SHIPPING = pid("ord_", "BOISF3");
    private static final String ORDER_INSIDE_BOUNDARY = pid("ord_", "BOISF4");
    private static final String ORDER_OUTSIDE_BOUNDARY = pid("ord_", "BOISF5");
    private static final String ORDER_EXPIRED = pid("ord_", "BOISF6");
    private static final String ORDER_OTHER_BUYER = pid("ord_", "BOISF7");

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
    @DisplayName("T1 일치 품목이 있는 주문만: SHIPPING 품목 없는 주문 제외·주문일 내림차순")
    void filter_includesOnlyOrdersWithMatchingItem() throws Exception {
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER)).param("itemStatus", "SHIPPING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].orderId",
                        contains(ORDER_TWO_SHIPPING, ORDER_ONE_SHIPPING, ORDER_INSIDE_BOUNDARY)));
    }

    @Test
    @DisplayName("T2 한 주문에 일치 품목 2개 → 주문 1건(EXISTS·중복 없음)·품목은 그대로 3개")
    void filter_multipleMatchingItems_countedOnce() throws Exception {
        String twoShippingPath = "$.items[?(@.orderId == '" + ORDER_TWO_SHIPPING + "')]";
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER)).param("itemStatus", "SHIPPING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(twoShippingPath, hasSize(1)))
                .andExpect(jsonPath(twoShippingPath + ".items[*]", hasSize(3)))
                .andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    @DisplayName("T3 기간: 3개월 − 2일 전 주문 포함·3개월 + 2일 전 주문 제외")
    void filter_excludesOrdersOutsidePeriod() throws Exception {
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER)).param("itemStatus", "SHIPPING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.orderId == '" + ORDER_INSIDE_BOUNDARY + "')]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.orderId == '" + ORDER_OUTSIDE_BOUNDARY + "')]", hasSize(0)));
    }

    @Test
    @DisplayName("T4 다른 구매자 주문 제외: 각자 자기 주문만")
    void filter_excludesOtherBuyer() throws Exception {
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER)).param("itemStatus", "SHIPPING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.orderId == '" + ORDER_OTHER_BUYER + "')]", hasSize(0)));
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(OTHER_BUYER)).param("itemStatus", "SHIPPING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].orderId", contains(ORDER_OTHER_BUYER)))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("T5 totalCount: size=1(count 쿼리 실행) → 필터 조건 기준 3·페이지 이동")
    void filter_totalCountAcrossPages() throws Exception {
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER))
                        .param("itemStatus", "SHIPPING").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].orderId", contains(ORDER_TWO_SHIPPING)))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER))
                        .param("itemStatus", "SHIPPING").param("size", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].orderId", contains(ORDER_INSIDE_BOUNDARY)))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T6 파라미터 없음: 기존 결과 그대로(기간 제한 없음·PAYMENT_EXPIRED만 제외)")
    void noParam_keepsExistingResult() throws Exception {
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].orderId", contains(ORDER_TWO_SHIPPING, ORDER_ONE_SHIPPING,
                        ORDER_NO_SHIPPING, ORDER_INSIDE_BOUNDARY, ORDER_OUTSIDE_BOUNDARY)))
                .andExpect(jsonPath("$.totalCount").value(5));
    }

    @Test
    @DisplayName("T7 잘못된 값(BOGUS)·단계 밖 품목 상태(ORDERED)·소문자(shipping) → 400 MALFORMED_REQUEST")
    void filter_invalidValue_returns400() throws Exception {
        for (String invalid : new String[] {"BOGUS", "ORDERED", "shipping"}) {
            mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER)).param("itemStatus", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    @Test
    @DisplayName("T8 교차 확인: 요약 단계별 품목 수 = 같은 단계 필터 결과 속 해당 상태 품목 수(5단계 전부·0 아님)")
    void filter_matchesSummaryStageCounts() throws Exception {
        String summaryJson = mockMvc.perform(get(SUMMARY_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String stageStatus : STAGE_STATUSES) {
            Integer summaryCount = JsonPath.read(summaryJson, "$.stages." + stageStatus.toLowerCase(Locale.ROOT));
            assertThat(summaryCount).as("시드가 단계 %s 품목을 가져야 교차 확인이 의미 있다", stageStatus).isPositive();
            mockMvc.perform(get(ORDERS_URL).headers(authHeaders.buyer(BUYER_USER))
                            .param("itemStatus", stageStatus).param("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].items[?(@.status.code == '" + stageStatus + "')]",
                            hasSize(summaryCount)));
        }
    }

    @Test
    @DisplayName("T9 ADMIN → 403 FORBIDDEN(/api/v1/orders는 BUYER 전용)")
    void list_admin_returns403() throws Exception {
        mockMvc.perform(get(ORDERS_URL).headers(authHeaders.admin(ADMIN_USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------- seed·helpers (BuyerOrderStatusSummaryIntegrationTest 패턴) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * BUYER 주문 6건(주문일 내림차순):
     * TWO_SHIPPING(1일 전: SHIPPING·SHIPPING·PAID) · ONE_SHIPPING(2일 전: SHIPPING·DELIVERED·PREPARING) ·
     * NO_SHIPPING(3일 전: CONFIRMED·CANCELLED) · EXPIRED(4일 전·PAYMENT_EXPIRED: ORDERED) ·
     * INSIDE(3개월 − 2일 전: SHIPPING·DELIVERED) · OUTSIDE(3개월 + 2일 전: SHIPPING·CONFIRMED).
     * OTHER_BUYER 주문 1건(1일 전: SHIPPING).
     */
    private void seedGraph() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodStart = now.minusMonths(SUMMARY_PERIOD_MONTHS);
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                insertUser(BUYER_USER, "BOISFUSR1");
                insertUser(OTHER_BUYER, "BOISFUSR2");
                insertUser(ADMIN_USER, "BOISFUSR3");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '필터셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BOISFSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '필터상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "BOISFPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCBOISF', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "BOISFVAR"), PRODUCT_ID, DUMMY_FK_ID);

                insertOrder(ID_BASE, ORDER_TWO_SHIPPING, BUYER_USER, "PAID", now.minusDays(1));
                insertOrder(ID_BASE + 1, ORDER_ONE_SHIPPING, BUYER_USER, "PAID", now.minusDays(2));
                insertOrder(ID_BASE + 2, ORDER_NO_SHIPPING, BUYER_USER, "PAID", now.minusDays(3));
                insertOrder(ID_BASE + 3, ORDER_EXPIRED, BUYER_USER, "PAYMENT_EXPIRED", now.minusDays(4));
                insertOrder(ID_BASE + 4, ORDER_INSIDE_BOUNDARY, BUYER_USER, "PAID",
                        periodStart.plusDays(BOUNDARY_MARGIN_DAYS));
                insertOrder(ID_BASE + 5, ORDER_OUTSIDE_BOUNDARY, BUYER_USER, "PAID",
                        periodStart.minusDays(BOUNDARY_MARGIN_DAYS));
                insertOrder(ID_BASE + 6, ORDER_OTHER_BUYER, OTHER_BUYER, "PAID", now.minusDays(1));

                insertItem(ID_BASE, ID_BASE, "SHIPPING");
                insertItem(ID_BASE + 1, ID_BASE, "SHIPPING");
                insertItem(ID_BASE + 2, ID_BASE, "PAID");
                insertItem(ID_BASE + 3, ID_BASE + 1, "SHIPPING");
                insertItem(ID_BASE + 4, ID_BASE + 1, "DELIVERED");
                insertItem(ID_BASE + 5, ID_BASE + 1, "PREPARING");
                insertItem(ID_BASE + 6, ID_BASE + 2, "CONFIRMED");
                insertItem(ID_BASE + 7, ID_BASE + 2, "CANCELLED");
                insertItem(ID_BASE + 8, ID_BASE + 3, "ORDERED");
                insertItem(ID_BASE + 9, ID_BASE + 4, "SHIPPING");
                insertItem(ID_BASE + 10, ID_BASE + 4, "DELIVERED");
                insertItem(ID_BASE + 11, ID_BASE + 5, "SHIPPING");
                insertItem(ID_BASE + 12, ID_BASE + 5, "CONFIRMED");
                insertItem(ID_BASE + 13, ID_BASE + 6, "SHIPPING");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertUser(long id, String tag) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", id, pid("usr_", tag));
    }

    private void insertOrder(long id, String publicId, long buyerId, String orderStatus, LocalDateTime orderedAt) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, NOW(6), NOW(6))",
                id, publicId, buyerId, "ORDBOISF" + id, orderStatus, ITEM_PRICE, orderedAt);
    }

    private void insertItem(long id, long orderId, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '필터상품', 1000)",
                id, pid("oit_", "BOISFIT" + id), orderId, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
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

package com.zslab.mall.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.stats.controller.response.AdminSalesBreakdownResponse;
import com.zslab.mall.stats.controller.response.AdminSalesStatsResponse;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 매출 통계 통합 테스트(Track 90-E-1·D-200·실 MariaDB·HTTP 경유·{@code SellerDashboardQueryControllerIntegrationTest} 시드 방식).
 * 집계가 셀러 범위라 시드 셀러(A·B·C)의 응답은 다른 테스트의 잔여 행에 영향받지 않아 절대값으로 단언한다. 시드 시각은 2026-03 중심이며
 * 기간을 명시해 실행일에 독립적이다.
 *
 * <p><b>시드 그래프</b>: 셀러 A(상태 파라미터)·B(ACTIVE)·C(ACTIVE·데이터 0) / 카테고리 1·2 / 상품 PA(A·카테고리1)·PA2(A·카테고리2)·PB(B·카테고리1) /
 * variant VA1(PA)·VA2(PA·"색상: 블랙")·VA3(PA2)·VB(PB) /
 * 주문 M(혼합·PAID·03-10·total 47,000 = 품목 45,000 + 배송비 3,000 − 할인 1,000) — A1(PA/VA1·10,000) + A2(PA2/VA3·2개·20,000) + B1(PB/VB·15,000) /
 * N(A·03-20) — A3(PA/VA2·5,000·옵션 라벨) / X(PENDING_PAYMENT·paid_at null) — A4(99,000) / Z(A·03-31 23:59:59) — A6(PA/VA1·7,000) /
 * W(A·03-01 00:00:00) — A7(PA2/VA3·6,000) / P(A·02-15·직전 기간) — A8(PA/VA1·3,000) / Y(A·2025-03-10·전년 동기) — A9(PA/VA1·4,000).
 * 클레임: A1 CANCEL(환불 COMPLETED 10,000 03-13 + PENDING 999) · B1 RETURN(환불 COMPLETED 15,000 03-15).
 * 셀러 A 3월 = 매출 48,000·주문 4(M·N·Z·W)·수량 6·환불 10,000.
 */
@AutoConfigureMockMvc
class SellerSalesStatsQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/seller/stats/sales";
    private static final String BREAKDOWN_URL = URL + "/breakdown";
    private static final String EXPORT_URL = URL + "/export";
    private static final String DASHBOARD_URL = "/api/v1/seller/dashboard";

    private static final long ID_BASE = 9880L;
    private static final long USER_A = ID_BASE;
    private static final long USER_B = ID_BASE + 1;
    private static final long USER_C = ID_BASE + 2;
    private static final long BUYER_ID = ID_BASE + 3;
    private static final long SELLER_A = ID_BASE;
    private static final long SELLER_B = ID_BASE + 1;
    private static final long SELLER_C = ID_BASE + 2;
    private static final long CATEGORY_1 = ID_BASE;
    private static final long CATEGORY_2 = ID_BASE + 1;
    private static final long PRODUCT_A = ID_BASE;
    private static final long PRODUCT_A2 = ID_BASE + 1;
    private static final long PRODUCT_B = ID_BASE + 2;
    private static final long VARIANT_A1 = ID_BASE;
    private static final long VARIANT_A2 = ID_BASE + 1;
    private static final long VARIANT_A3 = ID_BASE + 2;
    private static final long VARIANT_B = ID_BASE + 3;
    private static final long ORDER_M = ID_BASE;
    private static final long ORDER_N = ID_BASE + 1;
    private static final long ORDER_X = ID_BASE + 2;
    private static final long ORDER_Z = ID_BASE + 3;
    private static final long ORDER_W = ID_BASE + 4;
    private static final long ORDER_P = ID_BASE + 5;
    private static final long ORDER_Y = ID_BASE + 6;
    private static final long ITEM_A1 = ID_BASE;
    private static final long ITEM_A2 = ID_BASE + 1;
    private static final long ITEM_B1 = ID_BASE + 2;
    private static final long ITEM_A3 = ID_BASE + 3;
    private static final long ITEM_A4 = ID_BASE + 4;
    private static final long ITEM_A6 = ID_BASE + 5;
    private static final long ITEM_A7 = ID_BASE + 6;
    private static final long ITEM_A8 = ID_BASE + 7;
    private static final long ITEM_A9 = ID_BASE + 8;
    private static final long CLAIM_A1 = ID_BASE;
    private static final long CLAIM_B1 = ID_BASE + 1;
    private static final long REFUND_BASE = ID_BASE;
    private static final long DUMMY_FK_ID = ID_BASE;

    private static final long ITEM_A1_PRICE = 10_000L;
    private static final long ITEM_A2_PRICE = 20_000L;
    private static final long ITEM_B1_PRICE = 15_000L;
    private static final long ITEM_A3_PRICE = 5_000L;
    private static final long ITEM_A6_PRICE = 7_000L;
    private static final long ITEM_A7_PRICE = 6_000L;
    private static final long ITEM_A8_PRICE = 3_000L;
    private static final long ITEM_A9_PRICE = 4_000L;
    private static final long ORDER_M_SHIPPING_FEE = 3_000L;
    private static final long ORDER_M_DISCOUNT = 1_000L;
    private static final long ORDER_M_TOTAL = ITEM_A1_PRICE + ITEM_A2_PRICE + ITEM_B1_PRICE + ORDER_M_SHIPPING_FEE - ORDER_M_DISCOUNT;
    private static final long REFUND_A1_COMPLETED = 10_000L;
    private static final long REFUND_A1_PENDING = 999L;
    private static final long REFUND_B1_COMPLETED = 15_000L;
    private static final long SELLER_A_MARCH_REVENUE = ITEM_A1_PRICE + ITEM_A2_PRICE + ITEM_A3_PRICE + ITEM_A6_PRICE + ITEM_A7_PRICE;
    private static final long SELLER_A_MARCH_ORDERS = 4;
    private static final long SELLER_A_MARCH_QUANTITY = 6;
    private static final long PRODUCT_A_MARCH_REVENUE = ITEM_A1_PRICE + ITEM_A3_PRICE + ITEM_A6_PRICE;
    private static final long PRODUCT_A2_MARCH_REVENUE = ITEM_A2_PRICE + ITEM_A7_PRICE;
    private static final int MARCH_DAYS = 31;
    private static final int MARCH_ISO_WEEKS = 6;

    private static final String PRODUCT_A_PID = pid("prd_", "SSTPA");
    private static final String PRODUCT_A2_PID = pid("prd_", "SSTPA2");
    private static final String PRODUCT_B_PID = pid("prd_", "SSTPB");
    private static final String VARIANT_A1_PID = pid("var_", "SSTVA1");
    private static final String VARIANT_A2_PID = pid("var_", "SSTVA2");
    private static final String VARIANT_A3_PID = pid("var_", "SSTVA3");
    private static final String VARIANT_B_PID = pid("var_", "SSTVB");
    private static final String ITEM_B1_PID = pid("oit_", "SSTB1");
    private static final String OPTION_LABEL_A2 = "색상: 블랙";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 응답 키 화이트리스트 — 필드가 늘면 여기와 해당 SellerSales* record를 함께 바꿔야 한다. */
    private static final Set<String> STATS_TOP_KEYS = Set.of("summary", "compareSummary", "trend", "compareTrend");
    private static final Set<String> SUMMARY_KEYS = Set.of("revenue", "refund", "netRevenue", "orderCount", "itemQuantity",
            "avgOrderValue", "avgItemsPerOrder");
    private static final Set<String> TREND_KEYS = Set.of("bucketKey", "bucketLabel", "revenue", "refund", "netRevenue", "orderCount");
    private static final Set<String> BREAKDOWN_TOP_KEYS = Set.of("axis", "totalRevenue", "rows");
    private static final Set<String> ROW_KEYS = Set.of("key", "name", "revenue", "share", "orderCount", "quantity", "compareRevenue");
    private static final Set<String> SELLER_ALLOWED_KEYS = union(STATS_TOP_KEYS, SUMMARY_KEYS, TREND_KEYS, BREAKDOWN_TOP_KEYS, ROW_KEYS);
    /** 수동 목록: 구매자·타 셀러·주문 총액·드릴다운 축. 관리자 DTO에서 사라져도 여기 항목은 남는다. */
    private static final Set<String> MANUAL_FORBIDDEN_KEYS = Set.of("buyer", "buyerId", "buyerName", "sellerId", "sellerName",
            "sellerPublicId", "parentKey", "drillable", "discountAmount", "shippingFee", "totalPrice");
    private static final Set<String> FORBIDDEN_KEYS = forbiddenKeys();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedAll(SellerStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인가: 3 endpoint 모두 비인증 401 · 구매자 403 · 셀러 200")
    void authorization() throws Exception {
        for (String url : List.of(URL + "?from=2026-03-01&to=2026-03-31",
                BREAKDOWN_URL + "?from=2026-03-01&to=2026-03-31&axis=PRODUCT",
                EXPORT_URL + "?from=2026-03-01&to=2026-03-31&axis=PRODUCT")) {
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(url).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
            mockMvc.perform(get(url).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("T2 격리·혼합 주문 M(03-10): A.revenue = A1+A2 · B.revenue = B1 · A+B ≠ order.total_price · orderCount DISTINCT · 타 셀러 상품·품목 0")
    void mixedOrder_ownItemsOnly() throws Exception {
        JsonNode sellerA = fetch(USER_A, URL + "?from=2026-03-10&to=2026-03-10");
        JsonNode sellerB = fetch(USER_B, URL + "?from=2026-03-10&to=2026-03-10");

        long revenueA = sellerA.get("summary").get("revenue").asLong();
        long revenueB = sellerB.get("summary").get("revenue").asLong();
        assertThat(revenueA).isEqualTo(ITEM_A1_PRICE + ITEM_A2_PRICE);
        assertThat(revenueB).isEqualTo(ITEM_B1_PRICE);
        assertThat(sellerA.get("summary").get("orderCount").asLong()).isEqualTo(1);
        assertThat(sellerA.get("summary").get("itemQuantity").asLong()).isEqualTo(3);
        assertThat(sellerA.get("summary").get("avgOrderValue").asLong()).isEqualTo(ITEM_A1_PRICE + ITEM_A2_PRICE);
        assertThat(sellerA.get("summary").get("avgItemsPerOrder").asDouble()).isEqualTo(3.0);
        // 배송비·할인은 주문 축이라 셀러 매출 합(품목 축)은 order.total_price와 의도적으로 다르다 — 같으면 주문 총액을 쓴 것
        Long orderTotal = jdbc.queryForObject("SELECT total_price FROM `order` WHERE id = ?", Long.class, ORDER_M);
        assertThat(orderTotal).isEqualTo(ORDER_M_TOTAL);
        assertThat(revenueA + revenueB).isNotEqualTo(orderTotal);
        assertThat(sellerA.get("trend").get(0).get("revenue").asLong()).isEqualTo(ITEM_A1_PRICE + ITEM_A2_PRICE);
        assertThat(sellerA.get("trend").get(0).get("orderCount").asLong()).isEqualTo(1);

        JsonNode breakdownA = fetch(USER_A, BREAKDOWN_URL + "?from=2026-03-10&to=2026-03-10&axis=PRODUCT");
        assertThat(breakdownA.get("totalRevenue").asLong()).isEqualTo(ITEM_A1_PRICE + ITEM_A2_PRICE);
        assertThat(breakdownA.get("rows")).hasSize(2);
        assertThat(breakdownA.toString()).doesNotContain(PRODUCT_B_PID).doesNotContain("통계상품B").doesNotContain(ITEM_B1_PID);
        JsonNode breakdownB = fetch(USER_B, BREAKDOWN_URL + "?from=2026-03-10&to=2026-03-10&axis=PRODUCT");
        assertThat(breakdownB.get("rows")).hasSize(1);
        assertThat(breakdownB.get("rows").get(0).get("key").asText()).isEqualTo(PRODUCT_B_PID);
        assertThat(breakdownB.get("rows").get(0).get("share").asDouble()).isEqualTo(100.0);

        // 데이터 0 셀러 C: 요약 전부 0·trend 1행 0·분해 빈 배열
        JsonNode sellerC = fetch(USER_C, URL + "?from=2026-03-10&to=2026-03-10");
        for (String key : SUMMARY_KEYS) {
            assertThat(sellerC.get("summary").get(key).asDouble()).as("summary." + key).isZero();
        }
        assertThat(sellerC.get("trend")).hasSize(1);
        assertThat(fetch(USER_C, BREAKDOWN_URL + "?from=2026-03-10&to=2026-03-10&axis=OPTION").get("rows")).isEmpty();
    }

    @Test
    @DisplayName("T3 셀러 대시보드 항등: 3월 요약(매출·환불·순매출·주문수)이 /seller/dashboard 같은 기간과 일치 · 환불은 A1 COMPLETED만(PENDING·B1 제외)")
    void summaryMatchesDashboard() throws Exception {
        JsonNode stats = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31").get("summary");
        JsonNode dashboard = fetch(USER_A, DASHBOARD_URL + "?from=2026-03-01&to=2026-03-31").get("summary");

        for (String key : List.of("revenue", "refund", "netRevenue", "orderCount")) {
            assertThat(stats.get(key).asLong()).as(key).isEqualTo(dashboard.get(key).asLong());
        }
        assertThat(stats.get("revenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE);
        assertThat(stats.get("refund").asLong()).isEqualTo(REFUND_A1_COMPLETED);
        assertThat(stats.get("netRevenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE - REFUND_A1_COMPLETED);
        assertThat(stats.get("orderCount").asLong()).isEqualTo(SELLER_A_MARCH_ORDERS);
        assertThat(stats.get("itemQuantity").asLong()).isEqualTo(SELLER_A_MARCH_QUANTITY);
        assertThat(stats.get("avgOrderValue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE / SELLER_A_MARCH_ORDERS);
        assertThat(stats.get("avgItemsPerOrder").asDouble()).isEqualTo(1.5);
        assertThat(fetch(USER_B, URL + "?from=2026-03-01&to=2026-03-31").get("summary").get("refund").asLong())
                .isEqualTo(REFUND_B1_COMPLETED);
    }

    @Test
    @DisplayName("T4 기간 경계: 양끝 포함(03-01 00:00·03-31 23:59:59) · 좁히면 제외 · 미결제 제외 · 365일 OK/366일 400 · from>to 400 · 형식 오류 400")
    void periodBoundaries() throws Exception {
        JsonNode march = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31");
        assertThat(march.get("summary").get("revenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE);
        assertThat(march.toString()).doesNotContain("99000");
        JsonNode inner = fetch(USER_A, URL + "?from=2026-03-02&to=2026-03-30");
        assertThat(inner.get("summary").get("revenue").asLong())
                .isEqualTo(SELLER_A_MARCH_REVENUE - ITEM_A7_PRICE - ITEM_A6_PRICE);

        // 2025-04-01 ~ 2026-03-31 = 365일(허용) · 2025-03-31 ~ = 366일(400)
        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A)).param("from", "2025-04-01").param("to", "2026-03-31")
                        .param("unit", "MONTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend.length()").value(12));
        for (String url : List.of(URL + "?from=2025-03-31&to=2026-03-31", BREAKDOWN_URL + "?from=2025-03-31&to=2026-03-31&axis=PRODUCT",
                EXPORT_URL + "?from=2025-03-31&to=2026-03-31&axis=PRODUCT", URL + "?from=2026-03-11&to=2026-03-10",
                BREAKDOWN_URL + "?from=2026-03-11&to=2026-03-10&axis=OPTION", URL + "?from=2026/03/01&to=2026-03-31",
                BREAKDOWN_URL + "?from=2026-03-01&to=2026-03-31&axis=SELLER", BREAKDOWN_URL + "?from=2026-03-01&to=2026-03-31",
                URL + "?from=2026-03-01&to=2026-03-31&unit=HOUR")) {
            mockMvc.perform(get(url).headers(authHeaders.seller(USER_A)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    @Test
    @DisplayName("T5 비교 기간: PREVIOUS(직전 31일·02-15 3,000) · YEAR_AGO(2025-03·4,000) · NONE 필드 생략 · 비교 데이터 0이면 생략 · compareTrend 길이 = trend")
    void comparePeriods() throws Exception {
        JsonNode previous = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31&compare=PREVIOUS");
        assertThat(previous.get("compareSummary").get("revenue").asLong()).isEqualTo(ITEM_A8_PRICE);
        assertThat(previous.get("compareSummary").get("orderCount").asLong()).isEqualTo(1);
        assertThat(previous.get("compareTrend")).hasSize(MARCH_DAYS);
        // 직전 기간 = 01-29 ~ 02-28 → 02-15는 인덱스 17
        assertThat(previous.get("compareTrend").get(17).get("bucketKey").asText()).isEqualTo("2026-02-15");
        assertThat(previous.get("compareTrend").get(17).get("revenue").asLong()).isEqualTo(ITEM_A8_PRICE);

        JsonNode yearAgo = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31&compare=YEAR_AGO");
        assertThat(yearAgo.get("compareSummary").get("revenue").asLong()).isEqualTo(ITEM_A9_PRICE);
        assertThat(yearAgo.get("compareTrend").get(9).get("bucketKey").asText()).isEqualTo("2025-03-10");

        JsonNode none = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31");
        assertThat(none.has("compareSummary")).isFalse();
        assertThat(none.has("compareTrend")).isFalse();
        // 2026-06 직전(05-01~05-31)에는 데이터 0 → 생략
        JsonNode empty = fetch(USER_A, URL + "?from=2026-06-01&to=2026-06-30&compare=PREVIOUS");
        assertThat(empty.has("compareSummary")).isFalse();
    }

    @Test
    @DisplayName("T6 unit=DAY/WEEK/MONTH: 3월 31일·ISO 6주(02-23 월요일 시작 2026-W09~W14)·1월 · 빈 구간 0 · 환불은 refunded_at 구간")
    void buckets() throws Exception {
        JsonNode daily = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31&unit=DAY").get("trend");
        assertThat(daily).hasSize(MARCH_DAYS);
        assertThat(daily.get(0).get("bucketKey").asText()).isEqualTo("2026-03-01");
        assertThat(daily.get(0).get("revenue").asLong()).isEqualTo(ITEM_A7_PRICE);
        assertThat(daily.get(1).get("revenue").asLong()).isZero();
        assertThat(daily.get(9).get("revenue").asLong()).isEqualTo(ITEM_A1_PRICE + ITEM_A2_PRICE);
        assertThat(daily.get(12).get("refund").asLong()).isEqualTo(REFUND_A1_COMPLETED);
        assertThat(daily.get(12).get("netRevenue").asLong()).isEqualTo(-REFUND_A1_COMPLETED);
        assertThat(daily.get(MARCH_DAYS - 1).get("revenue").asLong()).isEqualTo(ITEM_A6_PRICE);

        JsonNode weekly = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31&unit=WEEK").get("trend");
        assertThat(weekly).hasSize(MARCH_ISO_WEEKS);
        assertThat(weekly.get(0).get("bucketKey").asText()).isEqualTo("2026-W09");
        assertThat(weekly.get(0).get("bucketLabel").asText()).isEqualTo("2026-02-23");
        assertThat(weekly.get(0).get("revenue").asLong()).isEqualTo(ITEM_A7_PRICE);
        assertThat(weekly.get(MARCH_ISO_WEEKS - 1).get("bucketKey").asText()).isEqualTo("2026-W14");
        assertThat(weekly.get(MARCH_ISO_WEEKS - 1).get("revenue").asLong()).isEqualTo(ITEM_A6_PRICE);

        JsonNode monthly = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31&unit=MONTH").get("trend");
        assertThat(monthly).hasSize(1);
        assertThat(monthly.get(0).get("bucketKey").asText()).isEqualTo("2026-03");
        assertThat(monthly.get(0).get("revenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE);
        assertThat(monthly.get(0).get("refund").asLong()).isEqualTo(REFUND_A1_COMPLETED);
        assertThat(monthly.get(0).get("orderCount").asLong()).isEqualTo(SELLER_A_MARCH_ORDERS);
    }

    @Test
    @DisplayName("T7 축 3종: PRODUCT(스냅샷 이름·public_id·비중) · OPTION(variant 그룹·\"상품명 / 옵션라벨\"·단순상품 \"(옵션 없음)\") · CATEGORY(현행 표시명) · compareRevenue 키 없으면 생략")
    void breakdownAxes() throws Exception {
        JsonNode product = fetch(USER_A, BREAKDOWN_URL + "?from=2026-03-01&to=2026-03-31&axis=PRODUCT&compare=PREVIOUS");
        assertThat(product.get("axis").asText()).isEqualTo("PRODUCT");
        assertThat(product.get("totalRevenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE);
        JsonNode productRows = product.get("rows");
        assertThat(productRows).hasSize(2);
        assertThat(productRows.get(0).get("key").asText()).isEqualTo(PRODUCT_A2_PID);
        assertThat(productRows.get(0).get("name").asText()).isEqualTo("통계상품A2");
        assertThat(productRows.get(0).get("revenue").asLong()).isEqualTo(PRODUCT_A2_MARCH_REVENUE);
        assertThat(productRows.get(0).get("share").asDouble()).isEqualTo(54.17);
        assertThat(productRows.get(0).get("orderCount").asLong()).isEqualTo(2);
        assertThat(productRows.get(0).get("quantity").asLong()).isEqualTo(3);
        assertThat(productRows.get(0).has("compareRevenue")).isFalse();
        assertThat(productRows.get(1).get("key").asText()).isEqualTo(PRODUCT_A_PID);
        assertThat(productRows.get(1).get("revenue").asLong()).isEqualTo(PRODUCT_A_MARCH_REVENUE);
        assertThat(productRows.get(1).get("share").asDouble()).isEqualTo(45.83);
        assertThat(productRows.get(1).get("compareRevenue").asLong()).isEqualTo(ITEM_A8_PRICE);

        JsonNode optionRows = fetch(USER_A, BREAKDOWN_URL + "?from=2026-03-01&to=2026-03-31&axis=OPTION").get("rows");
        assertThat(optionRows).hasSize(3);
        assertThat(optionRows.get(0).get("key").asText()).isEqualTo(VARIANT_A3_PID);
        assertThat(optionRows.get(0).get("name").asText()).isEqualTo("통계상품A2 / (옵션 없음)");
        assertThat(optionRows.get(0).get("revenue").asLong()).isEqualTo(PRODUCT_A2_MARCH_REVENUE);
        assertThat(optionRows.get(1).get("key").asText()).isEqualTo(VARIANT_A1_PID);
        assertThat(optionRows.get(1).get("revenue").asLong()).isEqualTo(ITEM_A1_PRICE + ITEM_A6_PRICE);
        assertThat(optionRows.get(2).get("key").asText()).isEqualTo(VARIANT_A2_PID);
        assertThat(optionRows.get(2).get("name").asText()).isEqualTo("통계상품A / " + OPTION_LABEL_A2);
        assertThat(optionRows.get(2).get("revenue").asLong()).isEqualTo(ITEM_A3_PRICE);
        assertThat(optionRows.toString()).doesNotContain(VARIANT_B_PID);

        JsonNode categoryRows = fetch(USER_A, BREAKDOWN_URL + "?from=2026-03-01&to=2026-03-31&axis=CATEGORY").get("rows");
        assertThat(categoryRows).hasSize(2);
        assertThat(categoryRows.get(0).get("key").asText()).isEqualTo(String.valueOf(CATEGORY_2));
        assertThat(categoryRows.get(0).get("name").asText()).isEqualTo("통계카테고리2");
        assertThat(categoryRows.get(0).get("revenue").asLong()).isEqualTo(PRODUCT_A2_MARCH_REVENUE);
        assertThat(categoryRows.get(1).get("key").asText()).isEqualTo(String.valueOf(CATEGORY_1));
        // 카테고리1에는 타 셀러 상품 PB(B1 15,000)도 있지만 셀러 A 품목만 합산된다
        assertThat(categoryRows.get(1).get("revenue").asLong()).isEqualTo(PRODUCT_A_MARCH_REVENUE);
    }

    @Test
    @DisplayName("T8 CSV: text/csv;charset=UTF-8 · Content-Disposition filename+filename* · BOM 선두 · 한글 헤더 · 행 수 = 축 키 수 · 비교기간 열")
    void csv() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        get(EXPORT_URL + "?from=2026-03-01&to=2026-03-31&axis=PRODUCT&compare=PREVIOUS")
                                .headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn().getResponse();
        String disposition = response.getHeader("Content-Disposition");
        assertThat(disposition).startsWith("attachment; filename=\"seller-sales-breakdown-product-2026-03-01_2026-03-31.csv\"; ");
        assertThat(disposition).contains("filename*=UTF-8''%EB%A7%A4%EC%B6%9C%ED%86%B5%EA%B3%84_%EC%83%81%ED%92%88_2026-03-01_2026-03-31.csv");

        byte[] bytes = response.getContentAsByteArray();
        assertThat(Arrays.copyOf(bytes, UTF8_BOM.length)).isEqualTo(UTF8_BOM);
        String body = new String(bytes, UTF8_BOM.length, bytes.length - UTF8_BOM.length, StandardCharsets.UTF_8);
        String[] lines = body.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("키,이름,매출,비중(%),주문수,수량,비교기간 매출");
        assertThat(lines[1]).isEqualTo(PRODUCT_A2_PID + ",통계상품A2," + PRODUCT_A2_MARCH_REVENUE + ",54.17,2,3,");
        assertThat(lines[2]).isEqualTo(PRODUCT_A_PID + ",통계상품A," + PRODUCT_A_MARCH_REVENUE + ",45.83,3,3," + ITEM_A8_PRICE);
        assertThat(body).doesNotContain("통계상품B");
    }

    @Test
    @DisplayName("T9 응답 키 화이트리스트: 요약 7·추이 6·분해 상위 3·행 7(NON_NULL 생략 허용) 정확 일치 · 관리자 전용·구매자·주문 총액 키 0")
    void responseKeyWhitelist() throws Exception {
        JsonNode stats = fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31&compare=PREVIOUS");
        assertThat(keysOf(stats)).containsExactlyInAnyOrderElementsOf(STATS_TOP_KEYS);
        assertThat(keysOf(stats.get("summary"))).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(keysOf(stats.get("compareSummary"))).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        for (JsonNode row : stats.get("trend")) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(TREND_KEYS);
        }
        JsonNode breakdown = fetch(USER_A, BREAKDOWN_URL + "?from=2026-03-01&to=2026-03-31&axis=PRODUCT&compare=PREVIOUS");
        assertThat(keysOf(breakdown)).containsExactlyInAnyOrderElementsOf(BREAKDOWN_TOP_KEYS);
        for (JsonNode row : breakdown.get("rows")) {
            assertThat(ROW_KEYS).containsAll(keysOf(row));
            assertThat(keysOf(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        }
        assertThat(keysOf(breakdown)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(keysOf(stats)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(stats.toString()).doesNotContain("buyer").doesNotContain("통계구매자");
        assertThat(breakdown.toString()).doesNotContain("통계구매자");
    }

    @Test
    @DisplayName("T10 D-190: SUSPENDED 셀러 GET 200(3 endpoint)")
    void suspended_returns200() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);

        assertThat(fetch(USER_A, URL + "?from=2026-03-01&to=2026-03-31").get("summary").get("revenue").asLong())
                .isEqualTo(SELLER_A_MARCH_REVENUE);
        mockMvc.perform(get(BREAKDOWN_URL + "?from=2026-03-01&to=2026-03-31&axis=CATEGORY").headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk());
        mockMvc.perform(get(EXPORT_URL + "?from=2026-03-01&to=2026-03-31&axis=OPTION").headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} 셀러 GET stats → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T11 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED")
    void sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedAll(status);

        mockMvc.perform(get(URL + "?from=2026-03-01&to=2026-03-31").headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- helpers ----------

    private JsonNode fetch(long userId, String url) throws Exception {
        String body = mockMvc.perform(get(url).headers(authHeaders.seller(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // ---------- seed·cleanup(? 바인딩·정적 SQL·SQL injection 위험 없음) ----------

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SSTUSA", "SSTSLA", "통계셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SSTUSB", "SSTSLB", "통계셀러B", SellerStatus.ACTIVE);
                seedSellerWithOwner(USER_C, SELLER_C, "SSTUSC", "SSTSLC", "통계셀러C", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, ?, '통계구매자', NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "SSTBUY"));
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '통계카테고리1', 1, 1, NOW(6), NOW(6))", CATEGORY_1);
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '통계카테고리2', 1, 2, NOW(6), NOW(6))", CATEGORY_2);

                seedProduct(PRODUCT_A, PRODUCT_A_PID, SELLER_A, CATEGORY_1, "통계상품A");
                seedProduct(PRODUCT_A2, PRODUCT_A2_PID, SELLER_A, CATEGORY_2, "통계상품A2");
                seedProduct(PRODUCT_B, PRODUCT_B_PID, SELLER_B, CATEGORY_1, "통계상품B");
                seedVariant(VARIANT_A1, VARIANT_A1_PID, PRODUCT_A);
                seedVariant(VARIANT_A2, VARIANT_A2_PID, PRODUCT_A);
                seedVariant(VARIANT_A3, VARIANT_A3_PID, PRODUCT_A2);
                seedVariant(VARIANT_B, VARIANT_B_PID, PRODUCT_B);

                seedOrder(ORDER_M, "SSTORM", "PAID", ORDER_M_TOTAL, ORDER_M_DISCOUNT, ORDER_M_SHIPPING_FEE,
                        "2026-03-10 09:00:00", "2026-03-10 09:05:00");
                seedOrder(ORDER_N, "SSTORN", "CONFIRMED", ITEM_A3_PRICE, 0, 0, "2026-03-20 09:00:00", "2026-03-20 10:00:00");
                seedOrder(ORDER_X, "SSTORX", "PENDING_PAYMENT", 99_000L, 0, 0, "2026-03-25 09:00:00", null);
                seedOrder(ORDER_Z, "SSTORZ", "PAID", ITEM_A6_PRICE, 0, 0, "2026-03-31 23:00:00", "2026-03-31 23:59:59");
                seedOrder(ORDER_W, "SSTORW", "SHIPPING", ITEM_A7_PRICE, 0, 0, "2026-02-28 23:00:00", "2026-03-01 00:00:00");
                seedOrder(ORDER_P, "SSTORP", "CONFIRMED", ITEM_A8_PRICE, 0, 0, "2026-02-15 09:00:00", "2026-02-15 10:00:00");
                seedOrder(ORDER_Y, "SSTORY", "CONFIRMED", ITEM_A9_PRICE, 0, 0, "2025-03-10 09:00:00", "2025-03-10 10:00:00");

                seedOrderItem(ITEM_A1, "SSTA1", ORDER_M, SELLER_A, PRODUCT_A, VARIANT_A1, "통계상품A", null, 1, ITEM_A1_PRICE, "CANCEL_REQUESTED");
                seedOrderItem(ITEM_A2, "SSTA2", ORDER_M, SELLER_A, PRODUCT_A2, VARIANT_A3, "통계상품A2", null, 2, ITEM_A2_PRICE, "PAID");
                seedOrderItem(ITEM_B1, "SSTB1", ORDER_M, SELLER_B, PRODUCT_B, VARIANT_B, "통계상품B", null, 1, ITEM_B1_PRICE, "PAID");
                seedOrderItem(ITEM_A3, "SSTA3", ORDER_N, SELLER_A, PRODUCT_A, VARIANT_A2, "통계상품A", OPTION_LABEL_A2, 1, ITEM_A3_PRICE,
                        "CONFIRMED");
                seedOrderItem(ITEM_A4, "SSTA4", ORDER_X, SELLER_A, PRODUCT_A, VARIANT_A1, "통계상품A", null, 1, 99_000L, "ORDERED");
                seedOrderItem(ITEM_A6, "SSTA6", ORDER_Z, SELLER_A, PRODUCT_A, VARIANT_A1, "통계상품A", null, 1, ITEM_A6_PRICE, "PAID");
                seedOrderItem(ITEM_A7, "SSTA7", ORDER_W, SELLER_A, PRODUCT_A2, VARIANT_A3, "통계상품A2", null, 1, ITEM_A7_PRICE, "SHIPPING");
                seedOrderItem(ITEM_A8, "SSTA8", ORDER_P, SELLER_A, PRODUCT_A, VARIANT_A1, "통계상품A", null, 1, ITEM_A8_PRICE, "CONFIRMED");
                seedOrderItem(ITEM_A9, "SSTA9", ORDER_Y, SELLER_A, PRODUCT_A, VARIANT_A1, "통계상품A", null, 1, ITEM_A9_PRICE, "CONFIRMED");

                seedClaim(CLAIM_A1, "SSTCA1", ITEM_A1, "CANCEL", "COMPLETED", "2026-03-12 10:00:00");
                seedClaim(CLAIM_B1, "SSTCB1", ITEM_B1, "RETURN", "COMPLETED", "2026-03-14 10:00:00");
                seedRefund(REFUND_BASE, CLAIM_A1, REFUND_A1_COMPLETED, "COMPLETED", "2026-03-13 10:00:00");
                seedRefund(REFUND_BASE + 1, CLAIM_A1, REFUND_A1_PENDING, "PENDING", null);
                seedRefund(REFUND_BASE + 2, CLAIM_B1, REFUND_B1_COMPLETED, "COMPLETED", "2026-03-15 10:00:00");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSellerWithOwner(long userId, long sellerId, String userTag, String sellerTag, String companyName,
            SellerStatus status) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", userTag));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", sellerTag), companyName, status.name());
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedProduct(long id, String publicId, long sellerId, long categoryId, String name) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, 0, NOW(6), NOW(6))",
                id, publicId, sellerId, categoryId, name);
    }

    private void seedVariant(long id, String publicId, long productId) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))", id, publicId, productId, "SST-" + id, DUMMY_FK_ID);
    }

    private void seedOrder(long id, String tag, String status, long totalPrice, long discountAmount, long shippingFee,
            String orderedAt, String paidAt) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("ord_", tag), BUYER_ID, "ORD" + tag + id, status, totalPrice, discountAmount, shippingFee, orderedAt, paidAt);
    }

    private void seedOrderItem(long id, String tag, long orderId, long sellerId, long productId, long variantId, String productName,
            String optionLabel, int quantity, long totalPrice, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, option_label, "
                + "quantity, unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1000, ?, NOW(6), NOW(6))",
                id, pid("oit_", tag), orderId, productId, variantId, sellerId, productName, optionLabel, quantity,
                totalPrice / quantity, totalPrice, itemStatus);
    }

    private void seedClaim(long id, String tag, long orderItemId, String type, String status, String requestedAt) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, requested_by, "
                + "requested_at, previous_order_item_status, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'BUYER_CHANGED_MIND', '통계사유상세', ?, ?, ?, 'PAID', 0, NOW(6), NOW(6))",
                id, pid("clm_", tag), orderItemId, type, status, BUYER_ID, requestedAt);
    }

    private void seedRefund(long id, long claimId, long amount, String status, String refundedAt) {
        jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, refunded_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("rfn_", "SSTR" + (id - REFUND_BASE)), claimId, DUMMY_FK_ID, amount, status, refundedAt);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM refund WHERE id BETWEEN ? AND ?", REFUND_BASE, REFUND_BASE + 2);
                jdbc.update("DELETE FROM claim WHERE id IN (?, ?)", CLAIM_A1, CLAIM_B1);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ITEM_A1, ITEM_A9);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ORDER_M, ORDER_Y);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", VARIANT_A1, VARIANT_B);
                jdbc.update("DELETE FROM product WHERE id IN (?, ?, ?)", PRODUCT_A, PRODUCT_A2, PRODUCT_B);
                jdbc.update("DELETE FROM category WHERE id IN (?, ?)", CATEGORY_1, CATEGORY_2);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?, ?)", USER_A, USER_B, USER_C);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?, ?)", SELLER_A, SELLER_B, SELLER_C);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?, ?)", USER_A, USER_B, USER_C, BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> result = new LinkedHashSet<>();
        for (Set<String> set : sets) {
            result.addAll(set);
        }
        return result;
    }

    /** 금지 키 = (관리자 매출 통계 응답 DTO 2종의 선언 필드 재귀 — 셀러 허용 키) ∪ 수동 목록(D-191·D-192 관례). */
    private static Set<String> forbiddenKeys() {
        Set<String> adminKeys = new LinkedHashSet<>();
        collectRecordKeys(AdminSalesStatsResponse.class, adminKeys);
        collectRecordKeys(AdminSalesBreakdownResponse.class, adminKeys);
        adminKeys.removeAll(SELLER_ALLOWED_KEYS);
        adminKeys.addAll(MANUAL_FORBIDDEN_KEYS);
        return Set.copyOf(adminKeys);
    }

    private static void collectRecordKeys(Class<?> type, Set<String> into) {
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            into.add(component.getName());
            Class<?> nested = component.getType();
            if (List.class.isAssignableFrom(nested) && component.getGenericType() instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                nested = element;
            }
            if (nested != type) {
                collectRecordKeys(nested, into);
            }
        }
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

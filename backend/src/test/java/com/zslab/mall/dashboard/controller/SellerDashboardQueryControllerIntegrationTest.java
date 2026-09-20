package com.zslab.mall.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.dashboard.controller.response.AdminDashboardResponse;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 대시보드 통합 테스트(Track 90-B-2·실 MariaDB·HTTP 경유). 집계가 셀러 범위로 잡히므로 시드 셀러(A·B·C)의 응답은 다른 테스트의
 * 잔여 행에 영향받지 않아 절대값으로 단언한다(관리자 대시보드 IT의 차분 방식과 다른 지점). 시드 시각은 전부 2026-03 고정이며 기간은
 * from/to를 명시해 실행일에 독립적이다(기본 기간 검사만 오늘 기준).
 *
 * <p><b>시드 그래프</b>: 셀러 A(user A·상태 파라미터)·셀러 B(ACTIVE)·셀러 C(ACTIVE·데이터 0) /
 * 주문 M(혼합·PAID·결제 03-10·total_price 47,000 = 품목 45,000 + 배송비 3,000 − 할인 1,000) — A1(상품A·10,000·CANCEL_REQUESTED) +
 * A2(상품A2·2개·20,000·PAID) + B1(상품B·15,000·PAID) / 주문 N(A·CONFIRMED·03-20) — A3(상품A·5,000·CONFIRMED) /
 * 주문 X(PENDING_PAYMENT·paid_at null) — A4(99,000·ORDERED) / 주문 Y(PAYMENT_EXPIRED·paid_at null) — A5(88,000·ORDERED) /
 * 주문 Z(A·PAID·03-31 23:59:59) — A6(상품A·7,000·PAID) / 주문 W(A·PAID·03-01 00:00:00) — A7(상품A2·6,000·SHIPPING).
 * 클레임: A1 CANCEL REQUESTED(03-12·환불 COMPLETED 10,000 + PENDING 999) · B1 RETURN REQUESTED(03-14·환불 COMPLETED 15,000) ·
 * A3 RETURN APPROVED(03-21). 정산: A PENDING·A CONFIRMED·B PENDING. 재고: A 가용 3(임박)·0·6·수동품절 2 / B 가용 2.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerDashboardQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/seller/dashboard";

    private static final long USER_A = 9660L;
    private static final long USER_B = 9661L;
    private static final long USER_C = 9662L;
    private static final long BUYER_ID = 9663L;
    private static final long SELLER_A = 9660L;
    private static final long SELLER_B = 9661L;
    private static final long SELLER_C = 9662L;
    private static final long PRODUCT_A = 9660L;
    private static final long PRODUCT_A2 = 9661L;
    private static final long PRODUCT_B = 9662L;
    private static final long VARIANT_BASE = 9660L;
    private static final long DUMMY_FK_ID = 9660L;
    private static final long ORDER_M = 9660L;
    private static final long ORDER_N = 9661L;
    private static final long ORDER_X = 9662L;
    private static final long ORDER_Y = 9663L;
    private static final long ORDER_Z = 9664L;
    private static final long ORDER_W = 9665L;
    private static final long ITEM_A1 = 9660L;
    private static final long ITEM_A2 = 9661L;
    private static final long ITEM_B1 = 9662L;
    private static final long ITEM_A3 = 9663L;
    private static final long ITEM_A4 = 9664L;
    private static final long ITEM_A5 = 9665L;
    private static final long ITEM_A6 = 9666L;
    private static final long ITEM_A7 = 9667L;
    private static final long CLAIM_A1 = 9660L;
    private static final long CLAIM_B1 = 9661L;
    private static final long CLAIM_A3 = 9662L;
    private static final long REFUND_BASE = 9660L;
    private static final long SETTLEMENT_BASE = 9660L;

    private static final long ITEM_A1_PRICE = 10_000L;
    private static final long ITEM_A2_PRICE = 20_000L;
    private static final long ITEM_B1_PRICE = 15_000L;
    private static final long ITEM_A3_PRICE = 5_000L;
    private static final long ITEM_A6_PRICE = 7_000L;
    private static final long ITEM_A7_PRICE = 6_000L;
    private static final long ORDER_M_SHIPPING_FEE = 3_000L;
    private static final long ORDER_M_DISCOUNT = 1_000L;
    private static final long ORDER_M_TOTAL = ITEM_A1_PRICE + ITEM_A2_PRICE + ITEM_B1_PRICE + ORDER_M_SHIPPING_FEE - ORDER_M_DISCOUNT;
    private static final long REFUND_A1_COMPLETED = 10_000L;
    private static final long REFUND_A1_PENDING = 999L;
    private static final long REFUND_B1_COMPLETED = 15_000L;
    private static final long SELLER_A_MARCH_REVENUE = ITEM_A1_PRICE + ITEM_A2_PRICE + ITEM_A3_PRICE + ITEM_A6_PRICE + ITEM_A7_PRICE;
    private static final int MARCH_DAYS = 31;
    private static final int DEFAULT_PERIOD_DAYS = 30;

    private static final String ORDER_M_NO = "ORDSDBM9660";
    private static final String ORDER_N_NO = "ORDSDBN9661";
    private static final String ITEM_A1_PID = pid("oit_", "SDBA1");
    private static final String ITEM_A2_PID = pid("oit_", "SDBA2");
    private static final String ITEM_B1_PID = pid("oit_", "SDBB1");
    private static final String ITEM_A3_PID = pid("oit_", "SDBA3");
    private static final String ITEM_A4_PID = pid("oit_", "SDBA4");
    private static final String ITEM_A5_PID = pid("oit_", "SDBA5");
    private static final String ITEM_A6_PID = pid("oit_", "SDBA6");
    private static final String ITEM_A7_PID = pid("oit_", "SDBA7");
    private static final String PRODUCT_A_PID = pid("prd_", "SDBPA");
    private static final String PRODUCT_A2_PID = pid("prd_", "SDBPA2");
    private static final String PRODUCT_B_PID = pid("prd_", "SDBPB");
    private static final String CLAIM_A1_PID = pid("clm_", "SDBCA1");
    private static final String CLAIM_A3_PID = pid("clm_", "SDBCA3");

    /** 응답 키 화이트리스트 — 필드가 늘면 여기와 해당 SellerDashboard* record를 함께 바꿔야 한다. */
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("period", "summary", "pending", "dailyTrend", "recentOrderItems",
            "recentClaims", "topProducts");
    private static final Set<String> PERIOD_KEYS = Set.of("from", "to");
    private static final Set<String> SUMMARY_KEYS = Set.of("revenue", "refund", "netRevenue", "orderCount");
    private static final Set<String> PENDING_KEYS = Set.of("deliveryReady", "claimRequested", "lowStock", "settlementPending");
    private static final Set<String> DAILY_TREND_KEYS = Set.of("date", "orderCount", "revenue");
    private static final Set<String> RECENT_ORDER_ITEM_KEYS = Set.of("orderItemId", "orderNo", "productName", "optionLabel",
            "quantity", "totalPrice", "itemStatus", "paidAt");
    private static final Set<String> RECENT_CLAIM_KEYS = Set.of("claimPublicId", "type", "status", "orderNo", "requestedAt");
    private static final Set<String> TOP_PRODUCT_KEYS = Set.of("productPublicId", "productName", "revenue", "quantity");
    private static final Set<String> SELLER_ALLOWED_KEYS = union(TOP_LEVEL_KEYS, PERIOD_KEYS, SUMMARY_KEYS, PENDING_KEYS,
            DAILY_TREND_KEYS, RECENT_ORDER_ITEM_KEYS, RECENT_CLAIM_KEYS, TOP_PRODUCT_KEYS);
    /** 수동 목록: 구매자·타 셀러·플랫폼 지표·주문 총액 축. 관리자 DTO에서 사라져도 여기 항목은 남는다. */
    private static final Set<String> MANUAL_FORBIDDEN_KEYS = Set.of("buyer", "buyerId", "buyerName", "buyerEmail", "requestedBy",
            "sellerId", "sellerName", "sellerPublicId", "topSellers", "newMemberCount", "monthlyRevenue", "previousDay",
            "previousMonth", "discountAmount", "shippingFee", "orderPublicId", "orderId", "reasonDetail", "attachmentUrls");
    /**
     * 셀러 노출 금지 키 = (관리자 대시보드 응답 DTO의 선언 필드 — 중첩 record·List&lt;record&gt; 포함 — 셀러 허용 키) ∪ 수동 목록
     * (D-191 관례). 관리자 대시보드에 필드가 추가되면 셀러 허용 집합에 없는 한 자동으로 금지 키가 된다.
     */
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
    @DisplayName("T1 인가: 비인증 401 · 구매자 403 · 셀러 200")
    void authorization() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 혼합 주문 M(03-10): A.revenue = A1+A2 · B.revenue = B1 · A+B ≠ order.total_price · orderCount DISTINCT(A 품목 2행 → 1) · 상위 상품은 자기 상품만")
    void mixedOrder_revenueIsOwnItemSum() throws Exception {
        JsonNode sellerA = fetch(USER_A, "2026-03-10", "2026-03-10");
        JsonNode sellerB = fetch(USER_B, "2026-03-10", "2026-03-10");

        long sellerARevenue = sellerA.get("summary").get("revenue").asLong();
        long sellerBRevenue = sellerB.get("summary").get("revenue").asLong();
        assertThat(sellerARevenue).isEqualTo(ITEM_A1_PRICE + ITEM_A2_PRICE);
        assertThat(sellerBRevenue).isEqualTo(ITEM_B1_PRICE);
        // A 품목 2행(A1·A2)이 든 주문 1건 → orderCount는 품목 행 수(2)가 아니라 주문 수(1)
        assertThat(sellerA.get("summary").get("orderCount").asLong()).isEqualTo(1);
        assertThat(sellerB.get("summary").get("orderCount").asLong()).isEqualTo(1);

        // 배송비·할인은 주문 축이라 셀러 매출 합(품목 축)은 order.total_price와 의도적으로 다르다 — 같으면 주문 총액을 쓴 것
        Long orderTotal = jdbc.queryForObject("SELECT total_price FROM `order` WHERE id = ?", Long.class, ORDER_M);
        assertThat(orderTotal).isEqualTo(ORDER_M_TOTAL);
        assertThat(sellerARevenue + sellerBRevenue).isNotEqualTo(orderTotal);

        // 일별 추이도 같은 정의(03-10 1행: DISTINCT 주문 1·자기 품목 합)
        JsonNode dailyA = sellerA.get("dailyTrend");
        assertThat(dailyA).hasSize(1);
        assertThat(dailyA.get(0).get("date").asText()).isEqualTo("2026-03-10");
        assertThat(dailyA.get(0).get("orderCount").asLong()).isEqualTo(1);
        assertThat(dailyA.get(0).get("revenue").asLong()).isEqualTo(ITEM_A1_PRICE + ITEM_A2_PRICE);
        assertThat(sellerB.get("dailyTrend").get(0).get("revenue").asLong()).isEqualTo(ITEM_B1_PRICE);

        // 상위 상품: A는 상품A2(20,000) → 상품A(10,000)·타 셀러 상품B 없음 / B는 상품B만
        JsonNode topA = sellerA.get("topProducts");
        assertThat(topA).hasSize(2);
        assertThat(topA.get(0).get("productPublicId").asText()).isEqualTo(PRODUCT_A2_PID);
        assertThat(topA.get(0).get("revenue").asLong()).isEqualTo(ITEM_A2_PRICE);
        assertThat(topA.get(0).get("quantity").asLong()).isEqualTo(2);
        assertThat(topA.get(1).get("productPublicId").asText()).isEqualTo(PRODUCT_A_PID);
        assertThat(topA.get(1).get("revenue").asLong()).isEqualTo(ITEM_A1_PRICE);
        assertThat(sellerA.toString()).doesNotContain(PRODUCT_B_PID).doesNotContain("대시상품B").doesNotContain(ITEM_B1_PID);
        JsonNode topB = sellerB.get("topProducts");
        assertThat(topB).hasSize(1);
        assertThat(topB.get(0).get("productPublicId").asText()).isEqualTo(PRODUCT_B_PID);
        assertThat(sellerB.toString()).doesNotContain(ITEM_A1_PID).doesNotContain(ITEM_A2_PID);
    }

    @Test
    @DisplayName("T3 환불 혼합: A.refund = A1 COMPLETED 10,000만(PENDING 999·B1 환불 제외) · B.refund = B1 15,000 · netRevenue = revenue − refund")
    void mixedOrder_refundIsOwnItemSum() throws Exception {
        JsonNode sellerA = fetch(USER_A, "2026-03-01", "2026-03-31");
        JsonNode sellerB = fetch(USER_B, "2026-03-01", "2026-03-31");

        assertThat(sellerA.get("summary").get("refund").asLong()).isEqualTo(REFUND_A1_COMPLETED);
        assertThat(sellerB.get("summary").get("refund").asLong()).isEqualTo(REFUND_B1_COMPLETED);
        assertThat(sellerA.get("summary").get("revenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE);
        assertThat(sellerA.get("summary").get("netRevenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE - REFUND_A1_COMPLETED);
        assertThat(sellerB.get("summary").get("netRevenue").asLong()).isEqualTo(ITEM_B1_PRICE - REFUND_B1_COMPLETED);
        // 3월 A 주문 = M·N·Z·W 4건(X·Y 미결제 제외)
        assertThat(sellerA.get("summary").get("orderCount").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("T4 미결제·만료 제외: PENDING_PAYMENT(A4)·PAYMENT_EXPIRED(A5) 품목은 매출·최근 목록 어디에도 없음 · 최근 품목 5 결제일 내림차순")
    void unpaidAndExpiredExcluded() throws Exception {
        JsonNode sellerA = fetch(USER_A, "2026-03-01", "2026-03-31");

        assertThat(sellerA.get("summary").get("revenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE);
        assertThat(sellerA.toString()).doesNotContain(ITEM_A4_PID).doesNotContain(ITEM_A5_PID)
                .doesNotContain("99000").doesNotContain("88000");

        JsonNode recent = sellerA.get("recentOrderItems");
        assertThat(recent).hasSize(5);
        assertThat(recent.get(0).get("orderItemId").asText()).isEqualTo(ITEM_A6_PID);
        assertThat(recent.get(1).get("orderItemId").asText()).isEqualTo(ITEM_A3_PID);
        assertThat(recent.get(1).get("orderNo").asText()).isEqualTo(ORDER_N_NO);
        // 동시각(주문 M)은 품목 id 내림차순
        assertThat(recent.get(2).get("orderItemId").asText()).isEqualTo(ITEM_A2_PID);
        assertThat(recent.get(3).get("orderItemId").asText()).isEqualTo(ITEM_A1_PID);
        assertThat(recent.get(3).get("orderNo").asText()).isEqualTo(ORDER_M_NO);
        assertThat(recent.get(3).get("itemStatus").asText()).isEqualTo("CANCEL_REQUESTED");
        assertThat(recent.get(3).get("totalPrice").asLong()).isEqualTo(ITEM_A1_PRICE);
        assertThat(recent.get(3).get("paidAt").asText()).endsWith("+09:00");
        assertThat(recent.get(4).get("orderItemId").asText()).isEqualTo(ITEM_A7_PID);
    }

    @Test
    @DisplayName("T5 기간 경계: 양끝 포함(03-01 00:00·03-31 23:59:59) · 좁히면 제외 · dailyTrend 31행 빈 날 0 · 92일 OK/93일 400 · from>to 400 · 형식 오류 400")
    void periodBoundaries() throws Exception {
        JsonNode march = fetch(USER_A, "2026-03-01", "2026-03-31");
        assertThat(march.get("period").get("from").asText()).isEqualTo("2026-03-01");
        assertThat(march.get("period").get("to").asText()).isEqualTo("2026-03-31");
        assertThat(march.get("summary").get("revenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE);
        JsonNode daily = march.get("dailyTrend");
        assertThat(daily).hasSize(MARCH_DAYS);
        assertThat(daily.get(0).get("date").asText()).isEqualTo("2026-03-01");
        assertThat(daily.get(0).get("revenue").asLong()).isEqualTo(ITEM_A7_PRICE);
        assertThat(daily.get(MARCH_DAYS - 1).get("date").asText()).isEqualTo("2026-03-31");
        assertThat(daily.get(MARCH_DAYS - 1).get("revenue").asLong()).isEqualTo(ITEM_A6_PRICE);
        assertThat(daily.get(1).get("revenue").asLong()).isZero();
        assertThat(daily.get(1).get("orderCount").asLong()).isZero();

        JsonNode inner = fetch(USER_A, "2026-03-02", "2026-03-30");
        assertThat(inner.get("summary").get("revenue").asLong())
                .isEqualTo(SELLER_A_MARCH_REVENUE - ITEM_A7_PRICE - ITEM_A6_PRICE);
        assertThat(inner.get("dailyTrend")).hasSize(MARCH_DAYS - 2);

        // 2026-01-01 ~ 04-02 = 92일(허용) · ~04-03 = 93일(400)
        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A)).param("from", "2026-01-01").param("to", "2026-04-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyTrend.length()").value(92));
        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A)).param("from", "2026-01-01").param("to", "2026-04-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A)).param("from", "2026-03-11").param("to", "2026-03-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A)).param("from", "2026/03/01"))
                .andExpect(status().isBadRequest());

        // 기본 기간 = 오늘 포함 최근 30일
        JsonNode defaults = fetch(USER_A, null, null);
        LocalDate today = LocalDate.now();
        assertThat(defaults.get("period").get("to").asText()).isEqualTo(today.toString());
        assertThat(defaults.get("period").get("from").asText()).isEqualTo(today.minusDays(DEFAULT_PERIOD_DAYS - 1).toString());
        assertThat(defaults.get("dailyTrend")).hasSize(DEFAULT_PERIOD_DAYS);
    }

    @Test
    @DisplayName("T6 빈 상태: 데이터 0 셀러 C → 200·요약/대기 전부 0·목록 빈 배열·dailyTrend 30행 0")
    void emptySeller() throws Exception {
        JsonNode sellerC = fetch(USER_C, null, null);

        for (String key : SUMMARY_KEYS) {
            assertThat(sellerC.get("summary").get(key).asLong()).as("summary." + key).isZero();
        }
        for (String key : PENDING_KEYS) {
            assertThat(sellerC.get("pending").get(key).asLong()).as("pending." + key).isZero();
        }
        assertThat(sellerC.get("recentOrderItems")).isEmpty();
        assertThat(sellerC.get("recentClaims")).isEmpty();
        assertThat(sellerC.get("topProducts")).isEmpty();
        assertThat(sellerC.get("dailyTrend")).hasSize(DEFAULT_PERIOD_DAYS);
        for (JsonNode row : sellerC.get("dailyTrend")) {
            assertThat(row.get("orderCount").asLong()).isZero();
            assertThat(row.get("revenue").asLong()).isZero();
        }
    }

    @Test
    @DisplayName("T7 처리 대기·최근 클레임: A = 배송 대기(PAID) 2·클레임 REQUESTED 1·재고 임박 1·정산 PENDING 1 / B = 1·1·1·1 · A 최근 클레임 A3→A1(B1 없음)")
    void pendingAndRecentClaims() throws Exception {
        JsonNode sellerA = fetch(USER_A, null, null);
        JsonNode sellerB = fetch(USER_B, null, null);

        assertThat(sellerA.get("pending").get("deliveryReady").asLong()).isEqualTo(2);
        assertThat(sellerA.get("pending").get("claimRequested").asLong()).isEqualTo(1);
        // A 가용 3(포함)·0·6·수동품절 2 → 1 / B 가용 2는 B 몫
        assertThat(sellerA.get("pending").get("lowStock").asLong()).isEqualTo(1);
        assertThat(sellerA.get("pending").get("settlementPending").asLong()).isEqualTo(1);
        assertThat(sellerB.get("pending").get("deliveryReady").asLong()).isEqualTo(1);
        assertThat(sellerB.get("pending").get("claimRequested").asLong()).isEqualTo(1);
        assertThat(sellerB.get("pending").get("lowStock").asLong()).isEqualTo(1);
        assertThat(sellerB.get("pending").get("settlementPending").asLong()).isEqualTo(1);

        JsonNode claimsA = sellerA.get("recentClaims");
        assertThat(claimsA).hasSize(2);
        assertThat(claimsA.get(0).get("claimPublicId").asText()).isEqualTo(CLAIM_A3_PID);
        assertThat(claimsA.get(0).get("type").asText()).isEqualTo("RETURN");
        assertThat(claimsA.get(0).get("status").asText()).isEqualTo("APPROVED");
        assertThat(claimsA.get(0).get("orderNo").asText()).isEqualTo(ORDER_N_NO);
        assertThat(claimsA.get(0).get("requestedAt").asText()).endsWith("+09:00");
        assertThat(claimsA.get(1).get("claimPublicId").asText()).isEqualTo(CLAIM_A1_PID);
        assertThat(claimsA.get(1).get("status").asText()).isEqualTo("REQUESTED");
        assertThat(sellerB.get("recentClaims")).hasSize(1);
        assertThat(sellerB.get("recentClaims").get(0).get("type").asText()).isEqualTo("RETURN");
    }

    @Test
    @DisplayName("T8 응답 키 화이트리스트: 최상위·period·summary·pending 정확 일치 · 배열 행(추이·최근 품목·최근 클레임·상위 상품) 고정 · 금지 키 0")
    void responseKeyWhitelist() throws Exception {
        JsonNode sellerA = fetch(USER_A, "2026-03-01", "2026-03-31");

        assertThat(keysOf(sellerA)).containsExactlyInAnyOrderElementsOf(TOP_LEVEL_KEYS);
        assertThat(keysOf(sellerA.get("period"))).containsExactlyInAnyOrderElementsOf(PERIOD_KEYS);
        assertThat(keysOf(sellerA.get("summary"))).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(keysOf(sellerA.get("pending"))).containsExactlyInAnyOrderElementsOf(PENDING_KEYS);
        for (JsonNode row : sellerA.get("dailyTrend")) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(DAILY_TREND_KEYS);
        }
        // NON_NULL 직렬화라 optionLabel(null)은 생략 → 부분집합 + 값 있는 키 정확 일치(시드 품목은 전부 optionLabel null)
        JsonNode recent = sellerA.get("recentOrderItems");
        assertThat(recent).isNotEmpty();
        Set<String> expectedRecentKeys = new LinkedHashSet<>(RECENT_ORDER_ITEM_KEYS);
        expectedRecentKeys.remove("optionLabel");
        for (JsonNode row : recent) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(expectedRecentKeys);
            assertThat(keysOf(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        }
        // 최근 클레임에 구매자(requestedBy)·사유·첨부가 없음을 고정 — 관리자 클레임 응답과 달리 5키뿐
        JsonNode claims = sellerA.get("recentClaims");
        assertThat(claims).isNotEmpty();
        for (JsonNode row : claims) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(RECENT_CLAIM_KEYS);
            assertThat(keysOf(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        }
        JsonNode topProducts = sellerA.get("topProducts");
        assertThat(topProducts).isNotEmpty();
        for (JsonNode row : topProducts) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(TOP_PRODUCT_KEYS);
            assertThat(keysOf(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        }
        assertThat(keysOf(sellerA)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(keysOf(sellerA.get("summary"))).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(sellerA.toString()).doesNotContain("buyer").doesNotContain("대시구매자");
    }

    @Test
    @DisplayName("T9 D-190: SUSPENDED 셀러 조회 200")
    void suspended_returns200() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);

        JsonNode sellerA = fetch(USER_A, "2026-03-01", "2026-03-31");
        assertThat(sellerA.get("summary").get("revenue").asLong()).isEqualTo(SELLER_A_MARCH_REVENUE);
    }

    @ParameterizedTest(name = "{0} 셀러 GET dashboard → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T10 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED")
    void sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedAll(status);

        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- helpers ----------

    private JsonNode fetch(long userId, String from, String to) throws Exception {
        MockHttpServletRequestBuilder request = get(URL).headers(authHeaders.seller(userId));
        if (from != null) {
            request = request.param("from", from);
        }
        if (to != null) {
            request = request.param("to", to);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // ---------- seed·cleanup(? 바인딩·정적 SQL·SQL injection 위험 없음) ----------

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SDBUSA", "SDBSLA", "대시셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SDBUSB", "SDBSLB", "대시셀러B", SellerStatus.ACTIVE);
                seedSellerWithOwner(USER_C, SELLER_C, "SDBUSC", "SDBSLC", "대시셀러C", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, ?, '대시구매자', NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "SDBBUY"));

                seedProduct(PRODUCT_A, PRODUCT_A_PID, SELLER_A, "대시상품A");
                seedProduct(PRODUCT_A2, PRODUCT_A2_PID, SELLER_A, "대시상품A2");
                seedProduct(PRODUCT_B, PRODUCT_B_PID, SELLER_B, "대시상품B");
                // 재고: A 가용 3(임박)·0(품절)·6(여유)·수동품절 2 / B 가용 2(임박·B 몫)
                seedVariantWithInventory(VARIANT_BASE, PRODUCT_A, false, 3);
                seedVariantWithInventory(VARIANT_BASE + 1, PRODUCT_A, false, 0);
                seedVariantWithInventory(VARIANT_BASE + 2, PRODUCT_A2, false, 6);
                seedVariantWithInventory(VARIANT_BASE + 3, PRODUCT_A2, true, 2);
                seedVariantWithInventory(VARIANT_BASE + 4, PRODUCT_B, false, 2);

                seedOrder(ORDER_M, pid("ord_", "SDBORM"), ORDER_M_NO, "PAID", ORDER_M_TOTAL, ORDER_M_DISCOUNT, ORDER_M_SHIPPING_FEE,
                        "2026-03-10 09:00:00", "2026-03-10 09:05:00");
                seedOrder(ORDER_N, pid("ord_", "SDBORN"), ORDER_N_NO, "CONFIRMED", ITEM_A3_PRICE, 0, 0,
                        "2026-03-20 09:00:00", "2026-03-20 10:00:00");
                seedOrder(ORDER_X, pid("ord_", "SDBORX"), "ORDSDBX9662", "PENDING_PAYMENT", 99_000L, 0, 0,
                        "2026-03-25 09:00:00", null);
                seedOrder(ORDER_Y, pid("ord_", "SDBORY"), "ORDSDBY9663", "PAYMENT_EXPIRED", 88_000L, 0, 0,
                        "2026-03-26 09:00:00", null);
                seedOrder(ORDER_Z, pid("ord_", "SDBORZ"), "ORDSDBZ9664", "PAID", ITEM_A6_PRICE, 0, 0,
                        "2026-03-31 23:00:00", "2026-03-31 23:59:59");
                seedOrder(ORDER_W, pid("ord_", "SDBORW"), "ORDSDBW9665", "SHIPPING", ITEM_A7_PRICE, 0, 0,
                        "2026-02-28 23:00:00", "2026-03-01 00:00:00");

                seedOrderItem(ITEM_A1, ITEM_A1_PID, ORDER_M, SELLER_A, PRODUCT_A, "대시상품A", 1, ITEM_A1_PRICE, "CANCEL_REQUESTED");
                seedOrderItem(ITEM_A2, ITEM_A2_PID, ORDER_M, SELLER_A, PRODUCT_A2, "대시상품A2", 2, ITEM_A2_PRICE, "PAID");
                seedOrderItem(ITEM_B1, ITEM_B1_PID, ORDER_M, SELLER_B, PRODUCT_B, "대시상품B", 1, ITEM_B1_PRICE, "PAID");
                seedOrderItem(ITEM_A3, ITEM_A3_PID, ORDER_N, SELLER_A, PRODUCT_A, "대시상품A", 1, ITEM_A3_PRICE, "CONFIRMED");
                seedOrderItem(ITEM_A4, ITEM_A4_PID, ORDER_X, SELLER_A, PRODUCT_A, "대시상품A", 1, 99_000L, "ORDERED");
                seedOrderItem(ITEM_A5, ITEM_A5_PID, ORDER_Y, SELLER_A, PRODUCT_A, "대시상품A", 1, 88_000L, "ORDERED");
                seedOrderItem(ITEM_A6, ITEM_A6_PID, ORDER_Z, SELLER_A, PRODUCT_A, "대시상품A", 1, ITEM_A6_PRICE, "PAID");
                seedOrderItem(ITEM_A7, ITEM_A7_PID, ORDER_W, SELLER_A, PRODUCT_A2, "대시상품A2", 1, ITEM_A7_PRICE, "SHIPPING");

                seedClaim(CLAIM_A1, CLAIM_A1_PID, ITEM_A1, "CANCEL", "REQUESTED", "2026-03-12 10:00:00");
                seedClaim(CLAIM_B1, pid("clm_", "SDBCB1"), ITEM_B1, "RETURN", "REQUESTED", "2026-03-14 10:00:00");
                seedClaim(CLAIM_A3, CLAIM_A3_PID, ITEM_A3, "RETURN", "APPROVED", "2026-03-21 10:00:00");
                seedRefund(REFUND_BASE, CLAIM_A1, REFUND_A1_COMPLETED, "COMPLETED", "2026-03-13 10:00:00");
                seedRefund(REFUND_BASE + 1, CLAIM_A1, REFUND_A1_PENDING, "PENDING", null);
                seedRefund(REFUND_BASE + 2, CLAIM_B1, REFUND_B1_COMPLETED, "COMPLETED", "2026-03-15 10:00:00");

                // uk_settlement_seller_period: 같은 셀러의 정산 2건은 기간이 달라야 한다(A PENDING 2월·A CONFIRMED 1월)
                seedSettlement(SETTLEMENT_BASE, SELLER_A, "PENDING", "2026-02-01 00:00:00", "2026-02-28 23:59:59");
                seedSettlement(SETTLEMENT_BASE + 1, SELLER_A, "CONFIRMED", "2026-01-01 00:00:00", "2026-01-31 23:59:59");
                seedSettlement(SETTLEMENT_BASE + 2, SELLER_B, "PENDING", "2026-02-01 00:00:00", "2026-02-28 23:59:59");
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

    private void seedProduct(long id, String publicId, long sellerId, String name) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, 0, NOW(6), NOW(6))",
                id, publicId, sellerId, DUMMY_FK_ID, name);
    }

    private void seedVariantWithInventory(long id, long productId, boolean soldoutManual, int available) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 0, 'SALE', ?, 1, ?, NOW(6), NOW(6))",
                id, pid("var_", "SDBV" + (id - VARIANT_BASE)), productId, "SDB-" + id, soldoutManual, DUMMY_FK_ID);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", id, id, available, available);
    }

    private void seedOrder(long id, String publicId, String orderNo, String status, long totalPrice, long discountAmount,
            long shippingFee, String orderedAt, String paidAt) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, publicId, BUYER_ID, orderNo, status, totalPrice, discountAmount, shippingFee, orderedAt, paidAt);
    }

    private void seedOrderItem(long id, String publicId, long orderId, long sellerId, long productId, String productName,
            int quantity, long totalPrice, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1000, ?, NOW(6), NOW(6))",
                id, publicId, orderId, productId, VARIANT_BASE, sellerId, productName, quantity, totalPrice / quantity, totalPrice,
                itemStatus);
    }

    private void seedClaim(long id, String publicId, long orderItemId, String type, String status, String requestedAt) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, requested_by, "
                + "requested_at, previous_order_item_status, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'CHANGE_MIND', '대시사유상세', ?, ?, ?, 'PAID', 0, NOW(6), NOW(6))",
                id, publicId, orderItemId, type, status, BUYER_ID, requestedAt);
    }

    private void seedRefund(long id, long claimId, long amount, String status, String refundedAt) {
        jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, refunded_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("rfn_", "SDBR" + (id - REFUND_BASE)), claimId, DUMMY_FK_ID, amount, status, refundedAt);
    }

    private void seedSettlement(long id, long sellerId, String status, String periodStart, String periodEnd) {
        jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, fee_amount, "
                + "refund_amount, net_amount, commission_rate, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 20000, 2000, 0, 18000, 1000, ?, NOW(6), NOW(6))",
                id, sellerId, DUMMY_FK_ID, periodStart, periodEnd, status);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM settlement WHERE id BETWEEN ? AND ?", SETTLEMENT_BASE, SETTLEMENT_BASE + 2);
                jdbc.update("DELETE FROM refund WHERE id BETWEEN ? AND ?", REFUND_BASE, REFUND_BASE + 2);
                jdbc.update("DELETE FROM claim WHERE id IN (?, ?, ?)", CLAIM_A1, CLAIM_B1, CLAIM_A3);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ITEM_A1, ITEM_A7);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ORDER_M, ORDER_W);
                jdbc.update("DELETE FROM inventory WHERE id BETWEEN ? AND ?", VARIANT_BASE, VARIANT_BASE + 4);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", VARIANT_BASE, VARIANT_BASE + 4);
                jdbc.update("DELETE FROM product WHERE id IN (?, ?, ?)", PRODUCT_A, PRODUCT_A2, PRODUCT_B);
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

    private static Set<String> forbiddenKeys() {
        Set<String> adminKeys = new LinkedHashSet<>();
        collectRecordKeys(AdminDashboardResponse.class, adminKeys);
        adminKeys.removeAll(SELLER_ALLOWED_KEYS);
        adminKeys.addAll(MANUAL_FORBIDDEN_KEYS);
        return Set.copyOf(adminKeys);
    }

    /** record 컴포넌트명을 모으고, 컴포넌트 타입이 record이거나 List&lt;record&gt;면 재귀한다(D-191 `SellerOrderItemQuery…IT`와 동형). */
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

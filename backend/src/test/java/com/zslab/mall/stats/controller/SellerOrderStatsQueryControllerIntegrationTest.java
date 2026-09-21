package com.zslab.mall.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.stats.controller.response.AdminOrderStatsResponse;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 주문·클레임 통계 통합 테스트(Track 90-E-2·D-200·실 MariaDB·HTTP 경유·{@code SellerSalesStatsQueryControllerIntegrationTest} 시드 방식).
 * 집계가 셀러 범위라 시드 셀러(A·B·C)의 응답은 다른 테스트의 잔여 행에 영향받지 않아 절대값으로 단언한다. 시드 시각은 2026-03 중심·기간 명시.
 *
 * <p><b>시드 그래프</b>: 셀러 A(상태 파라미터)·B(ACTIVE)·C(ACTIVE·데이터 0) / 상품 PA·PA2(A)·PB(B) /
 * 주문 M(혼합·PAID·03-10 09:00·total 47,000 = 품목 45,000 + 배송비 3,000 − 할인 1,000) — A1(PA·10,000) + A2(PA2·2개·20,000) + B1(PB·15,000) /
 * N(A·03-12) — A3(PA·5,000·미출고) / Z(A·03-20) — A4(PA2·7,000) / P(A·02-10·직전 기간) — A5(PA·3,000) / X(PENDING_PAYMENT) — A6(99,000).
 * 원 발송(OUTBOUND·claim NULL): A1 03-11 출고 → 03-13 완료(24h·48h) · A2 03-12 출고·미완료(48h) · A4 03-21 출고 → 03-25 완료(24h·96h) ·
 * B1 03-11 → 03-12 · A5 02-11 → 02-12. 회수(RETURN·claim CA2)·교환 재발송(OUTBOUND·claim CA3)은 퍼널·소요시간에서 제외.
 * 클레임: CA1 A1 CANCEL/BUYER_CHANGED_MIND 03-14(환불 COMPLETED 10,000 03-15 + PENDING 999) · CA2 A2 RETURN/PRODUCT_DEFECT 03-16(환불 5,000 03-17) ·
 * CA3 A4 EXCHANGE/PRODUCT_DEFECT 03-22 · CB1 B1 RETURN/BUYER_CHANGED_MIND 03-14(환불 15,000 03-15) · CA5 A5 CANCEL 02-12(환불 3,000 02-13).
 * 셀러 A 3월 = 결제 품목 4·출고 3·완료 2 · 클레임 3 · 환불 2건 15,000 · 매출 42,000.
 */
@AutoConfigureMockMvc
class SellerOrderStatsQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/seller/stats/orders";
    private static final String MARCH = "?from=2026-03-01&to=2026-03-31";

    private static final long ID_BASE = 9900L;
    private static final long USER_A = ID_BASE;
    private static final long USER_B = ID_BASE + 1;
    private static final long USER_C = ID_BASE + 2;
    private static final long BUYER_ID = ID_BASE + 3;
    private static final long SELLER_A = ID_BASE;
    private static final long SELLER_B = ID_BASE + 1;
    private static final long SELLER_C = ID_BASE + 2;
    private static final long PRODUCT_A = ID_BASE;
    private static final long PRODUCT_A2 = ID_BASE + 1;
    private static final long PRODUCT_B = ID_BASE + 2;
    private static final long ORDER_M = ID_BASE;
    private static final long ORDER_N = ID_BASE + 1;
    private static final long ORDER_Z = ID_BASE + 2;
    private static final long ORDER_P = ID_BASE + 3;
    private static final long ORDER_X = ID_BASE + 4;
    private static final long ITEM_A1 = ID_BASE;
    private static final long ITEM_A2 = ID_BASE + 1;
    private static final long ITEM_B1 = ID_BASE + 2;
    private static final long ITEM_A3 = ID_BASE + 3;
    private static final long ITEM_A4 = ID_BASE + 4;
    private static final long ITEM_A5 = ID_BASE + 5;
    private static final long ITEM_A6 = ID_BASE + 6;
    private static final long CLAIM_A1 = ID_BASE;
    private static final long CLAIM_A2 = ID_BASE + 1;
    private static final long CLAIM_A3 = ID_BASE + 2;
    private static final long CLAIM_B1 = ID_BASE + 3;
    private static final long CLAIM_A5 = ID_BASE + 4;
    private static final long DELIVERY_BASE = ID_BASE;
    private static final long DELIVERY_COUNT = 7;
    private static final long REFUND_BASE = ID_BASE;
    private static final long REFUND_COUNT = 5;
    private static final long DUMMY_FK_ID = ID_BASE;

    private static final long ITEM_A1_PRICE = 10_000L;
    private static final long ITEM_A2_PRICE = 20_000L;
    private static final long ITEM_B1_PRICE = 15_000L;
    private static final long ITEM_A3_PRICE = 5_000L;
    private static final long ITEM_A4_PRICE = 7_000L;
    private static final long ITEM_A5_PRICE = 3_000L;
    private static final long ORDER_M_TOTAL = ITEM_A1_PRICE + ITEM_A2_PRICE + ITEM_B1_PRICE + 3_000L - 1_000L;
    private static final long SELLER_A_MARCH_REVENUE = ITEM_A1_PRICE + ITEM_A2_PRICE + ITEM_A3_PRICE + ITEM_A4_PRICE;
    private static final long REFUND_A1 = 10_000L;
    private static final long REFUND_A2 = 5_000L;
    private static final long REFUND_B1 = 15_000L;
    private static final long REFUND_A5 = 3_000L;
    private static final long SELLER_A_MARCH_REFUND = REFUND_A1 + REFUND_A2;
    private static final int MARCH_DAYS = 31;
    private static final int MARCH_ISO_WEEKS = 6;

    private static final String PRODUCT_A_PID = pid("prd_", "SOSPA");
    private static final String PRODUCT_A2_PID = pid("prd_", "SOSPA2");
    private static final String PRODUCT_B_PID = pid("prd_", "SOSPB");
    private static final String ITEM_B1_PID = pid("oit_", "SOSB1");

    private static final Set<String> TOP_KEYS = Set.of("funnel", "leadTime", "claimSummary", "compareClaimSummary", "claimTrend",
            "compareClaimTrend", "claimByType", "claimByReason", "claimByProduct");
    private static final Set<String> FUNNEL_KEYS = Set.of("paidItems", "shippedItems", "deliveredItems");
    private static final Set<String> LEAD_TIME_KEYS = Set.of("paidToShipped", "shippedToDelivered");
    private static final Set<String> METRIC_KEYS = Set.of("avgHours", "medianHours", "count");
    private static final Set<String> SUMMARY_KEYS = Set.of("claimCount", "claimRate", "refundAmount", "refundRate", "refundCount", "paidItemCount");
    private static final Set<String> TREND_KEYS = Set.of("bucketKey", "bucketLabel", "claimCount", "claimRate", "refundAmount", "refundRate",
            "refundCount");
    private static final Set<String> TYPE_KEYS = Set.of("type", "count", "share");
    private static final Set<String> REASON_KEYS = Set.of("reasonCode", "count", "share");
    private static final Set<String> PRODUCT_KEYS = Set.of("productKey", "productName", "count", "share");
    private static final Set<String> SELLER_ALLOWED_KEYS = union(TOP_KEYS, FUNNEL_KEYS, LEAD_TIME_KEYS, METRIC_KEYS, SUMMARY_KEYS, TREND_KEYS,
            TYPE_KEYS, REASON_KEYS, PRODUCT_KEYS);
    /** 수동 목록: 구매자·타 셀러·주문 총액. 관리자 DTO에서 사라져도 여기 항목은 남는다. */
    private static final Set<String> MANUAL_FORBIDDEN_KEYS = Set.of("buyer", "buyerId", "buyerName", "requestedBy", "sellerId", "sellerName",
            "sellerPublicId", "discountAmount", "shippingFee", "totalPrice", "reasonDetail");
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
        mockMvc.perform(get(URL + MARCH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + MARCH).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + MARCH).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 격리·퍼널: 혼합 주문 M에서 A = 결제 4·출고 3·완료 2(부분 출고 A2·미출고 A3·회수/교환 재발송 제외) · B = 1·1·1 · C = 0 · 타 셀러 상품·품목 0")
    void funnel_ownItemsOnly() throws Exception {
        JsonNode sellerA = fetch(USER_A, MARCH);
        JsonNode sellerB = fetch(USER_B, MARCH);
        JsonNode sellerC = fetch(USER_C, MARCH);

        assertThat(sellerA.get("funnel").get("paidItems").asLong()).isEqualTo(4);
        assertThat(sellerA.get("funnel").get("shippedItems").asLong()).isEqualTo(3);
        assertThat(sellerA.get("funnel").get("deliveredItems").asLong()).isEqualTo(2);
        assertThat(sellerB.get("funnel").get("paidItems").asLong()).isEqualTo(1);
        assertThat(sellerB.get("funnel").get("shippedItems").asLong()).isEqualTo(1);
        assertThat(sellerB.get("funnel").get("deliveredItems").asLong()).isEqualTo(1);
        for (String key : FUNNEL_KEYS) {
            assertThat(sellerC.get("funnel").get(key).asLong()).as(key).isZero();
        }
        assertThat(sellerA.toString()).doesNotContain(PRODUCT_B_PID).doesNotContain("통계상품B").doesNotContain(ITEM_B1_PID);
        // 주문 M 총액(배송비·할인 포함)은 어느 셀러 응답에도 등장하지 않는다
        Long orderTotal = jdbc.queryForObject("SELECT total_price FROM `order` WHERE id = ?", Long.class, ORDER_M);
        assertThat(orderTotal).isEqualTo(ORDER_M_TOTAL);
        assertThat(sellerA.toString()).doesNotContain(String.valueOf(ORDER_M_TOTAL));
        // 빈 셀러: 소요시간 2구간 null(생략)·요약 0·분포 빈 배열
        assertThat(sellerC.get("leadTime").size()).isZero();
        assertThat(sellerC.get("claimSummary").get("claimCount").asLong()).isZero();
        assertThat(sellerC.get("claimByType")).isEmpty();
        assertThat(sellerC.get("claimByReason")).isEmpty();
        assertThat(sellerC.get("claimByProduct")).isEmpty();
    }

    @Test
    @DisplayName("T3 소요시간: 결제→출고 3표본(24·48·24h → 중앙값 24·평균 32) · 출고→완료 2표본(48·96h → 중앙값 72) · 종결 시각 기간 밖 제외 · 미출고 제외")
    void leadTime() throws Exception {
        JsonNode leadTime = fetch(USER_A, MARCH).get("leadTime");
        assertThat(leadTime.get("paidToShipped").get("count").asLong()).isEqualTo(3);
        assertThat(leadTime.get("paidToShipped").get("medianHours").asDouble()).isEqualTo(24.0);
        assertThat(leadTime.get("paidToShipped").get("avgHours").asDouble()).isEqualTo(32.0);
        assertThat(leadTime.get("shippedToDelivered").get("count").asLong()).isEqualTo(2);
        assertThat(leadTime.get("shippedToDelivered").get("medianHours").asDouble()).isEqualTo(72.0);
        assertThat(leadTime.get("shippedToDelivered").get("avgHours").asDouble()).isEqualTo(72.0);

        // 03-01~03-12: 출고 03-11(A1)·03-12(A2)만 표본(짝수 → 가운데 두 값 평균 36) · 완료 03-13 이후라 shippedToDelivered 생략
        JsonNode early = fetch(USER_A, "?from=2026-03-01&to=2026-03-12").get("leadTime");
        assertThat(early.get("paidToShipped").get("count").asLong()).isEqualTo(2);
        assertThat(early.get("paidToShipped").get("medianHours").asDouble()).isEqualTo(36.0);
        assertThat(early.has("shippedToDelivered")).isFalse();
        // 셀러 B: B1 출고 03-11(24h)·완료 03-12(24h)만 — A 표본 미혼입
        JsonNode sellerB = fetch(USER_B, MARCH).get("leadTime");
        assertThat(sellerB.get("paidToShipped").get("count").asLong()).isEqualTo(1);
        assertThat(sellerB.get("shippedToDelivered").get("medianHours").asDouble()).isEqualTo(24.0);
    }

    @Test
    @DisplayName("T4 클레임 요약·추이: 클레임 3/결제 품목 4 = 75% · 환불 15,000/매출 42,000(자기 품목·α) = 35.71%·2건 · DAY 31/WEEK 6/MONTH 1 · 각 구간 귀속(요청·결제·환불 시각)")
    void claimSummaryAndTrend() throws Exception {
        JsonNode march = fetch(USER_A, MARCH);
        JsonNode summary = march.get("claimSummary");
        assertThat(summary.get("claimCount").asLong()).isEqualTo(3);
        assertThat(summary.get("paidItemCount").asLong()).isEqualTo(4);
        assertThat(summary.get("claimRate").asDouble()).isEqualTo(75.0);
        assertThat(summary.get("refundAmount").asLong()).isEqualTo(SELLER_A_MARCH_REFUND);
        assertThat(summary.get("refundCount").asLong()).isEqualTo(2);
        assertThat(summary.get("refundRate").asDouble()).isEqualTo(35.71);
        // 셀러 B 환불 15,000은 A 응답에 없고 B에만
        assertThat(fetch(USER_B, MARCH).get("claimSummary").get("refundAmount").asLong()).isEqualTo(REFUND_B1);
        assertThat(fetch(USER_B, MARCH).get("claimSummary").get("refundRate").asDouble()).isEqualTo(100.0);

        JsonNode daily = march.get("claimTrend");
        assertThat(daily).hasSize(MARCH_DAYS);
        assertThat(daily.get(9).get("bucketKey").asText()).isEqualTo("2026-03-10");
        assertThat(daily.get(9).get("claimCount").asLong()).isZero();
        assertThat(daily.get(13).get("claimCount").asLong()).isEqualTo(1);
        assertThat(daily.get(14).get("refundAmount").asLong()).isEqualTo(REFUND_A1);
        assertThat(daily.get(14).get("refundCount").asLong()).isEqualTo(1);
        assertThat(daily.get(16).get("refundAmount").asLong()).isEqualTo(REFUND_A2);
        assertThat(daily.get(21).get("claimCount").asLong()).isEqualTo(1);
        assertThat(daily.get(0).get("claimRate").asDouble()).isZero();

        JsonNode weekly = fetch(USER_A, MARCH + "&unit=WEEK").get("claimTrend");
        assertThat(weekly).hasSize(MARCH_ISO_WEEKS);
        assertThat(weekly.get(0).get("bucketKey").asText()).isEqualTo("2026-W09");
        JsonNode monthly = fetch(USER_A, MARCH + "&unit=MONTH").get("claimTrend");
        assertThat(monthly).hasSize(1);
        assertThat(monthly.get(0).get("claimCount").asLong()).isEqualTo(3);
        assertThat(monthly.get(0).get("claimRate").asDouble()).isEqualTo(75.0);
        assertThat(monthly.get(0).get("refundAmount").asLong()).isEqualTo(SELLER_A_MARCH_REFUND);
        assertThat(monthly.get(0).get("refundRate").asDouble()).isEqualTo(35.71);
    }

    @Test
    @DisplayName("T5 분포: 유형 CANCEL/RETURN/EXCHANGE(ENUM 순) 각 1(33.33%) · 사유 PRODUCT_DEFECT 2(66.67%)→BUYER_CHANGED_MIND 1 · 상품별 PA2 2(public_id·스냅샷 이름)→PA 1")
    void distributions() throws Exception {
        JsonNode march = fetch(USER_A, MARCH);
        JsonNode byType = march.get("claimByType");
        assertThat(byType).hasSize(3);
        assertThat(byType.get(0).get("type").asText()).isEqualTo("CANCEL");
        // 동률 정렬 c.type ASC는 DB ENUM 선언 순(CANCEL·RETURN·EXCHANGE)이지 문자열 순이 아니다(관리자 동일)
        assertThat(byType.get(1).get("type").asText()).isEqualTo("RETURN");
        assertThat(byType.get(2).get("type").asText()).isEqualTo("EXCHANGE");
        assertThat(byType.get(0).get("share").asDouble()).isEqualTo(33.33);

        JsonNode byReason = march.get("claimByReason");
        assertThat(byReason).hasSize(2);
        assertThat(byReason.get(0).get("reasonCode").asText()).isEqualTo("PRODUCT_DEFECT");
        assertThat(byReason.get(0).get("count").asLong()).isEqualTo(2);
        assertThat(byReason.get(0).get("share").asDouble()).isEqualTo(66.67);
        assertThat(byReason.get(1).get("reasonCode").asText()).isEqualTo("BUYER_CHANGED_MIND");

        JsonNode byProduct = march.get("claimByProduct");
        assertThat(byProduct).hasSize(2);
        assertThat(byProduct.get(0).get("productKey").asText()).isEqualTo(PRODUCT_A2_PID);
        assertThat(byProduct.get(0).get("productName").asText()).isEqualTo("통계상품A2");
        assertThat(byProduct.get(0).get("count").asLong()).isEqualTo(2);
        assertThat(byProduct.get(0).get("share").asDouble()).isEqualTo(66.67);
        assertThat(byProduct.get(1).get("productKey").asText()).isEqualTo(PRODUCT_A_PID);
        assertThat(byProduct.get(1).get("count").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("T6 비교 기간: PREVIOUS(01-29~02-28·클레임 1·결제 품목 1·환불 3,000·compareClaimTrend 31) · YEAR_AGO 데이터 0 생략 · NONE 생략")
    void comparePeriods() throws Exception {
        JsonNode previous = fetch(USER_A, MARCH + "&compare=PREVIOUS");
        JsonNode compare = previous.get("compareClaimSummary");
        assertThat(compare.get("claimCount").asLong()).isEqualTo(1);
        assertThat(compare.get("paidItemCount").asLong()).isEqualTo(1);
        assertThat(compare.get("refundAmount").asLong()).isEqualTo(REFUND_A5);
        assertThat(compare.get("claimRate").asDouble()).isEqualTo(100.0);
        assertThat(previous.get("compareClaimTrend")).hasSize(MARCH_DAYS);
        // 01-29부터 02-12 = 인덱스 14
        assertThat(previous.get("compareClaimTrend").get(14).get("bucketKey").asText()).isEqualTo("2026-02-12");
        assertThat(previous.get("compareClaimTrend").get(14).get("claimCount").asLong()).isEqualTo(1);

        assertThat(fetch(USER_A, MARCH + "&compare=YEAR_AGO").has("compareClaimSummary")).isFalse();
        JsonNode none = fetch(USER_A, MARCH);
        assertThat(none.has("compareClaimSummary")).isFalse();
        assertThat(none.has("compareClaimTrend")).isFalse();
    }

    @Test
    @DisplayName("T7 기간 경계: 365일 OK/366일 400 · from>to 400 · 형식 오류 400 · unit·compare 허용 외 400 · 미결제 품목(A6) 미포함")
    void periodBoundaries() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A)).param("from", "2025-04-01").param("to", "2026-03-31").param("unit", "MONTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimTrend.length()").value(12));
        for (String query : List.of("?from=2025-03-31&to=2026-03-31", "?from=2026-03-11&to=2026-03-10", "?from=2026/03/01&to=2026-03-31",
                MARCH + "&unit=HOUR", MARCH + "&compare=LAST")) {
            mockMvc.perform(get(URL + query).headers(authHeaders.seller(USER_A)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        assertThat(fetch(USER_A, MARCH).toString()).doesNotContain("99000");
        assertThat(fetch(USER_A, MARCH).get("funnel").get("paidItems").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("T8 응답 키 화이트리스트: 최상위 9·퍼널 3·소요시간 2×3·요약 6·추이 7·분포 3/3/4 정확 일치 · 관리자 전용(confirmedItems·cancelledItems·returnedItems·claimRequestedToClosed)·구매자·주문 총액 키 0")
    void responseKeyWhitelist() throws Exception {
        JsonNode march = fetch(USER_A, MARCH + "&compare=PREVIOUS");
        assertThat(keysOf(march)).containsExactlyInAnyOrderElementsOf(TOP_KEYS);
        assertThat(keysOf(march.get("funnel"))).containsExactlyInAnyOrderElementsOf(FUNNEL_KEYS);
        assertThat(keysOf(march.get("leadTime"))).containsExactlyInAnyOrderElementsOf(LEAD_TIME_KEYS);
        for (String key : LEAD_TIME_KEYS) {
            assertThat(keysOf(march.get("leadTime").get(key))).containsExactlyInAnyOrderElementsOf(METRIC_KEYS);
        }
        assertThat(keysOf(march.get("claimSummary"))).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(keysOf(march.get("compareClaimSummary"))).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        for (JsonNode row : march.get("claimTrend")) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(TREND_KEYS);
        }
        for (JsonNode row : march.get("claimByType")) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(TYPE_KEYS);
        }
        for (JsonNode row : march.get("claimByReason")) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(REASON_KEYS);
        }
        for (JsonNode row : march.get("claimByProduct")) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(PRODUCT_KEYS);
        }
        assertThat(FORBIDDEN_KEYS).contains("confirmedItems", "cancelledItems", "returnedItems", "claimRequestedToClosed");
        assertThat(allKeys(march)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(march.toString()).doesNotContain("통계구매자").doesNotContain("통계사유상세");
    }

    @Test
    @DisplayName("T9 D-190: SUSPENDED 셀러 조회 200")
    void suspended_returns200() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);
        assertThat(fetch(USER_A, MARCH).get("claimSummary").get("claimCount").asLong()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0} 셀러 GET stats orders → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T10 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED")
    void sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedAll(status);
        mockMvc.perform(get(URL + MARCH).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- helpers ----------

    private JsonNode fetch(long userId, String query) throws Exception {
        String body = mockMvc.perform(get(URL + query).headers(authHeaders.seller(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // ---------- seed·cleanup(? 바인딩·정적 SQL·SQL injection 위험 없음) ----------

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SOSUSA", "SOSSLA", "통계셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SOSUSB", "SOSSLB", "통계셀러B", SellerStatus.ACTIVE);
                seedSellerWithOwner(USER_C, SELLER_C, "SOSUSC", "SOSSLC", "통계셀러C", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, ?, '통계구매자', NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "SOSBUY"));
                seedProduct(PRODUCT_A, PRODUCT_A_PID, SELLER_A, "통계상품A");
                seedProduct(PRODUCT_A2, PRODUCT_A2_PID, SELLER_A, "통계상품A2");
                seedProduct(PRODUCT_B, PRODUCT_B_PID, SELLER_B, "통계상품B");

                seedOrder(ORDER_M, "SOSORM", "PAID", ORDER_M_TOTAL, 1_000L, 3_000L, "2026-03-10 08:00:00", "2026-03-10 09:00:00");
                seedOrder(ORDER_N, "SOSORN", "PAID", ITEM_A3_PRICE, 0, 0, "2026-03-12 08:00:00", "2026-03-12 09:00:00");
                seedOrder(ORDER_Z, "SOSORZ", "PAID", ITEM_A4_PRICE, 0, 0, "2026-03-20 08:00:00", "2026-03-20 09:00:00");
                seedOrder(ORDER_P, "SOSORP", "CONFIRMED", ITEM_A5_PRICE, 0, 0, "2026-02-10 08:00:00", "2026-02-10 09:00:00");
                seedOrder(ORDER_X, "SOSORX", "PENDING_PAYMENT", 99_000L, 0, 0, "2026-03-25 09:00:00", null);

                seedOrderItem(ITEM_A1, "SOSA1", ORDER_M, SELLER_A, PRODUCT_A, "통계상품A", 1, ITEM_A1_PRICE, "CANCELLED");
                seedOrderItem(ITEM_A2, "SOSA2", ORDER_M, SELLER_A, PRODUCT_A2, "통계상품A2", 2, ITEM_A2_PRICE, "RETURNED");
                seedOrderItem(ITEM_B1, "SOSB1", ORDER_M, SELLER_B, PRODUCT_B, "통계상품B", 1, ITEM_B1_PRICE, "RETURNED");
                seedOrderItem(ITEM_A3, "SOSA3", ORDER_N, SELLER_A, PRODUCT_A, "통계상품A", 1, ITEM_A3_PRICE, "PAID");
                seedOrderItem(ITEM_A4, "SOSA4", ORDER_Z, SELLER_A, PRODUCT_A2, "통계상품A2", 1, ITEM_A4_PRICE, "EXCHANGE_REQUESTED");
                seedOrderItem(ITEM_A5, "SOSA5", ORDER_P, SELLER_A, PRODUCT_A, "통계상품A", 1, ITEM_A5_PRICE, "CANCELLED");
                seedOrderItem(ITEM_A6, "SOSA6", ORDER_X, SELLER_A, PRODUCT_A, "통계상품A", 1, 99_000L, "ORDERED");

                // 원 발송(OUTBOUND·claim NULL)
                seedDelivery(DELIVERY_BASE, ITEM_A1, "OUTBOUND", "DELIVERED", "2026-03-11 09:00:00", "2026-03-13 09:00:00", null);
                seedDelivery(DELIVERY_BASE + 1, ITEM_A2, "OUTBOUND", "SHIPPING", "2026-03-12 09:00:00", null, null);
                seedDelivery(DELIVERY_BASE + 2, ITEM_B1, "OUTBOUND", "DELIVERED", "2026-03-11 09:00:00", "2026-03-12 09:00:00", null);
                seedDelivery(DELIVERY_BASE + 3, ITEM_A4, "OUTBOUND", "DELIVERED", "2026-03-21 09:00:00", "2026-03-25 09:00:00", null);
                seedDelivery(DELIVERY_BASE + 4, ITEM_A5, "OUTBOUND", "DELIVERED", "2026-02-11 09:00:00", "2026-02-12 09:00:00", null);
                // 회수(RETURN·claim CA2)·교환 재발송(OUTBOUND·claim CA3) — 퍼널·소요시간 제외 대상
                seedDelivery(DELIVERY_BASE + 5, ITEM_A2, "RETURN", "DELIVERED", "2026-03-17 09:00:00", "2026-03-18 09:00:00", CLAIM_A2);
                seedDelivery(DELIVERY_BASE + 6, ITEM_A4, "OUTBOUND", "DELIVERED", "2026-03-23 09:00:00", "2026-03-24 09:00:00", CLAIM_A3);

                seedClaim(CLAIM_A1, "SOSCA1", ITEM_A1, "CANCEL", "BUYER_CHANGED_MIND", "COMPLETED", "2026-03-14 10:00:00");
                seedClaim(CLAIM_A2, "SOSCA2", ITEM_A2, "RETURN", "PRODUCT_DEFECT", "COMPLETED", "2026-03-16 10:00:00");
                seedClaim(CLAIM_A3, "SOSCA3", ITEM_A4, "EXCHANGE", "PRODUCT_DEFECT", "REQUESTED", "2026-03-22 10:00:00");
                seedClaim(CLAIM_B1, "SOSCB1", ITEM_B1, "RETURN", "BUYER_CHANGED_MIND", "COMPLETED", "2026-03-14 10:00:00");
                seedClaim(CLAIM_A5, "SOSCA5", ITEM_A5, "CANCEL", "BUYER_CHANGED_MIND", "COMPLETED", "2026-02-12 10:00:00");
                seedRefund(REFUND_BASE, CLAIM_A1, REFUND_A1, "COMPLETED", "2026-03-15 10:00:00");
                seedRefund(REFUND_BASE + 1, CLAIM_A1, 999L, "PENDING", null);
                seedRefund(REFUND_BASE + 2, CLAIM_A2, REFUND_A2, "COMPLETED", "2026-03-17 10:00:00");
                seedRefund(REFUND_BASE + 3, CLAIM_B1, REFUND_B1, "COMPLETED", "2026-03-15 10:00:00");
                seedRefund(REFUND_BASE + 4, CLAIM_A5, REFUND_A5, "COMPLETED", "2026-02-13 10:00:00");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSellerWithOwner(long userId, long sellerId, String userTag, String sellerTag, String companyName, SellerStatus status) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", userId, pid("usr_", userTag));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", sellerTag), companyName, status.name());
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedProduct(long id, String publicId, long sellerId, String name) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, 0, NOW(6), NOW(6))", id, publicId, sellerId, DUMMY_FK_ID, name);
    }

    private void seedOrder(long id, String tag, String status, long totalPrice, long discountAmount, long shippingFee, String orderedAt,
            String paidAt) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("ord_", tag), BUYER_ID, "ORD" + tag + id, status, totalPrice, discountAmount, shippingFee, orderedAt, paidAt);
    }

    private void seedOrderItem(long id, String tag, long orderId, long sellerId, long productId, String productName, int quantity,
            long totalPrice, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1000, ?, NOW(6), NOW(6))",
                id, pid("oit_", tag), orderId, productId, DUMMY_FK_ID, sellerId, productName, quantity, totalPrice / quantity, totalPrice,
                itemStatus);
    }

    private void seedDelivery(long id, long orderItemId, String direction, String status, String shippedAt, String deliveredAt, Long claimId) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                + "claim_id, created_at, updated_at) VALUES (?, ?, ?, ?, 'CJ', ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("dlv_", "SOSD" + (id - DELIVERY_BASE)), orderItemId, direction, "SOS-" + id, status, shippedAt, deliveredAt, claimId);
    }

    private void seedClaim(long id, String tag, long orderItemId, String type, String reasonCode, String status, String requestedAt) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, requested_by, "
                + "requested_at, previous_order_item_status, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, '통계사유상세', ?, ?, ?, 'PAID', 0, NOW(6), NOW(6))",
                id, pid("clm_", tag), orderItemId, type, reasonCode, status, BUYER_ID, requestedAt);
    }

    private void seedRefund(long id, long claimId, long amount, String status, String refundedAt) {
        jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, refunded_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("rfn_", "SOSR" + (id - REFUND_BASE)), claimId, DUMMY_FK_ID, amount, status, refundedAt);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM refund WHERE id BETWEEN ? AND ?", REFUND_BASE, REFUND_BASE + REFUND_COUNT - 1);
                jdbc.update("DELETE FROM delivery WHERE id BETWEEN ? AND ?", DELIVERY_BASE, DELIVERY_BASE + DELIVERY_COUNT - 1);
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", CLAIM_A1, CLAIM_A5);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ITEM_A1, ITEM_A6);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ORDER_M, ORDER_X);
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

    /** 금지 키 = (관리자 주문·클레임 통계 응답 DTO 선언 필드 재귀 − 셀러 허용 키) ∪ 수동 목록(D-191·D-192 관례). */
    private static Set<String> forbiddenKeys() {
        Set<String> adminKeys = new LinkedHashSet<>();
        collectRecordKeys(AdminOrderStatsResponse.class, adminKeys);
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

    /** 응답 트리 전체의 키(중첩 객체·배열 요소 포함). */
    private static Set<String> allKeys(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fields().forEachRemaining(entry -> {
            keys.add(entry.getKey());
            keys.addAll(allKeys(entry.getValue()));
        });
        node.elements().forEachRemaining(element -> keys.addAll(allKeys(element)));
        return keys;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

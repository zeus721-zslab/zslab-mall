package com.zslab.mall.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 매출 통계 통합 테스트(Track 87·D-181). 집계는 DB 전역이라 다른 테스트가 쓰지 않는 <b>고정 과거 기간(2019-12~2020-01)</b>에 시드해
 * 격리하고 정확값을 검증한다(대시보드 T4 차분 방식 대신 — 기간을 임의 지정할 수 있으므로 격리가 가능). 대사(T10)만 오늘 기준 시드로
 * 대시보드 응답과 직접 비교한다.
 *
 * <p>조회 기간 PERIOD = 2020-01-06(월)~2020-01-12(일) = ISO 2020-W02. 직전 동일 길이 = 2019-12-30~2020-01-05 = ISO 2020-W01(연도 경계).
 */
@AutoConfigureMockMvc
class AdminSalesStatsQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/stats/sales";
    private static final String BREAKDOWN_URL = URL + "/breakdown";
    private static final String CSV_URL = URL + "/breakdown.csv";
    private static final long ADMIN_ID = 987900L;
    private static final long ID_BASE = 987000L;
    private static final long ID_END = 987999L;
    private static final long BUYER = ID_BASE + 1;
    private static final long CATEGORY_A = ID_BASE + 1;
    private static final long CATEGORY_B = ID_BASE + 2;
    private static final long SELLER_A = ID_BASE + 1;
    private static final long SELLER_B = ID_BASE + 2;
    private static final long PRODUCT_A = ID_BASE + 1;
    private static final long PRODUCT_B = ID_BASE + 2;
    private static final long PRODUCT_C = ID_BASE + 3;
    private static final String PRODUCT_B_NAME = "통계상품,B";
    private static final long ORDER_START = ID_BASE + 1;
    private static final long ORDER_MID = ID_BASE + 2;
    private static final long ORDER_LAST_SECOND = ID_BASE + 3;
    private static final long ORDER_AFTER_END = ID_BASE + 4;
    private static final long ORDER_PREV_WEEK = ID_BASE + 5;
    private static final long ORDER_BEFORE_START = ID_BASE + 6;
    private static final long ORDER_PENDING = ID_BASE + 7;
    private static final long ORDER_THIS_MONTH = ID_BASE + 8;
    private static final long ITEM_MID_B = ID_BASE + 2;
    private static final long ITEM_PREV_WEEK = ID_BASE + 5;
    private static final long CLAIM_MID = ID_BASE + 1;
    private static final long CLAIM_PREV = ID_BASE + 2;
    private static final LocalDate FROM = LocalDate.of(2020, 1, 6);
    private static final LocalDate TO = LocalDate.of(2020, 1, 12);
    private static final long REVENUE = 45_000L;
    private static final long REFUND = 3_000L;
    private static final long PREV_REVENUE = 21_000L;
    private static final long PREV_REFUND = 2_000L;
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private record SeedOrder(long id, String status, long total, LocalDateTime paidAt) {
    }

    private record SeedItem(long id, long orderId, long productId, long sellerId, int quantity, long total) {
    }

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
    private LocalDateTime thisMonthNoon;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        thisMonthNoon = YearMonth.now().atDay(1).atTime(12, 0);
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인가: 비인증 401 · BUYER 403 · ADMIN 200(3 엔드포인트)")
    void authorization() throws Exception {
        String query = "?from=2020-01-06&to=2020-01-12&axis=SELLER";
        for (String url : List.of(URL, BREAKDOWN_URL, CSV_URL)) {
            mockMvc.perform(get(url + query)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(url + query).headers(authHeaders.buyer(BUYER))).andExpect(status().isForbidden());
            mockMvc.perform(get(url + query).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("T2 요약·경계: from 00:00:00 포함·to 익일 00:00:00 제외·미결제 제외·환불 COMPLETED만·객단가·주문당 품목수")
    void summaryAndBoundary() throws Exception {
        JsonNode summary = fetch(URL + "?from=2020-01-06&to=2020-01-12").get("summary");
        assertThat(summary.get("revenue").asLong()).isEqualTo(REVENUE);
        assertThat(summary.get("refund").asLong()).isEqualTo(REFUND);
        assertThat(summary.get("netRevenue").asLong()).isEqualTo(REVENUE - REFUND);
        assertThat(summary.get("orderCount").asLong()).isEqualTo(3);
        assertThat(summary.get("itemQuantity").asLong()).isEqualTo(5);
        assertThat(summary.get("avgOrderValue").asLong()).isEqualTo(15_000L);
        assertThat(summary.get("avgItemsPerOrder").asDouble()).isEqualTo(1.67);
        // record의 boolean 판정 메서드가 Jackson 프로퍼티로 새지 않는지(라이브 검증에서 "empty" 누출 발견)
        assertThat(summary.fieldNames()).toIterable().containsExactlyInAnyOrder("revenue", "refund", "netRevenue",
                "orderCount", "itemQuantity", "avgOrderValue", "avgItemsPerOrder");
        // 2020-01-13 00:00:00 주문(7000)은 to=2020-01-12 조회에서 제외·to=2020-01-13이면 포함
        assertThat(fetch(URL + "?from=2020-01-06&to=2020-01-13").get("summary").get("revenue").asLong())
                .isEqualTo(REVENUE + 7_000L);
    }

    @Test
    @DisplayName("T3 unit=DAY/WEEK/MONTH: 구간 수 고정·키 형식·빈 구간 0")
    void units() throws Exception {
        JsonNode daily = fetch(URL + "?from=2020-01-06&to=2020-01-12&unit=DAY").get("trend");
        assertThat(daily).hasSize(7);
        assertThat(keys(daily)).containsExactly("2020-01-06", "2020-01-07", "2020-01-08", "2020-01-09", "2020-01-10",
                "2020-01-11", "2020-01-12");
        assertBucket(daily.get(0), "2020-01-06", 10_000L, 0L, 1L);
        assertBucket(daily.get(1), "2020-01-07", 0L, 0L, 0L);
        assertBucket(daily.get(2), "2020-01-08", 30_000L, 0L, 1L);
        assertBucket(daily.get(4), "2020-01-10", 0L, REFUND, 0L);
        assertBucket(daily.get(6), "2020-01-12", 5_000L, 0L, 1L);

        JsonNode weekly = fetch(URL + "?from=2019-12-30&to=2020-01-12&unit=WEEK").get("trend");
        assertThat(keys(weekly)).containsExactly("2020-W01", "2020-W02");
        assertBucket(weekly.get(0), "2019-12-30", PREV_REVENUE, PREV_REFUND, 2L);
        assertBucket(weekly.get(1), "2020-01-06", REVENUE, REFUND, 3L);

        JsonNode monthly = fetch(URL + "?from=2019-12-01&to=2020-01-31&unit=MONTH").get("trend");
        assertThat(keys(monthly)).containsExactly("2019-12", "2020-01");
        assertBucket(monthly.get(0), "2019-12", 20_000L, 0L, 1L);
        assertBucket(monthly.get(1), "2020-01", 53_000L, REFUND + PREV_REFUND, 5L);
    }

    @Test
    @DisplayName("T4 주 단위: bucketLabel은 월요일·수요일 시작 조회도 그 주 월요일부터·연도 경계(2019-12-30 = 2020-W01)")
    void weekLabels() throws Exception {
        JsonNode trend = fetch(URL + "?from=2020-01-08&to=2020-01-12&unit=WEEK").get("trend");
        assertThat(trend).hasSize(1);
        assertThat(trend.get(0).get("bucketKey").asText()).isEqualTo("2020-W02");
        assertThat(trend.get(0).get("bucketLabel").asText()).isEqualTo("2020-01-06");
        assertThat(trend.get(0).get("revenue").asLong()).isEqualTo(35_000L);

        JsonNode yearBoundary = fetch(URL + "?from=2019-12-28&to=2020-01-01&unit=WEEK").get("trend");
        assertThat(keys(yearBoundary)).containsExactly("2019-W52", "2020-W01");
        assertThat(yearBoundary.get(0).get("bucketLabel").asText()).isEqualTo("2019-12-23");
        assertThat(yearBoundary.get(1).get("bucketLabel").asText()).isEqualTo("2019-12-30");
        assertThat(yearBoundary.get(1).get("revenue").asLong()).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("T5 compare=PREVIOUS 직전 7일 값·compareTrend 인덱스 대응 / YEAR_AGO 데이터 없음 → null / NONE → null")
    void compare() throws Exception {
        JsonNode previous = fetch(URL + "?from=2020-01-06&to=2020-01-12&unit=DAY&compare=PREVIOUS");
        JsonNode compareSummary = previous.get("compareSummary");
        assertThat(compareSummary.get("revenue").asLong()).isEqualTo(PREV_REVENUE);
        assertThat(compareSummary.get("refund").asLong()).isEqualTo(PREV_REFUND);
        assertThat(compareSummary.get("orderCount").asLong()).isEqualTo(2);
        assertThat(compareSummary.get("itemQuantity").asLong()).isEqualTo(3);
        JsonNode compareTrend = previous.get("compareTrend");
        assertThat(compareTrend).hasSize(previous.get("trend").size());
        assertThat(keys(compareTrend)).containsExactly("2019-12-30", "2019-12-31", "2020-01-01", "2020-01-02",
                "2020-01-03", "2020-01-04", "2020-01-05");
        assertBucket(compareTrend.get(0), "2019-12-30", 20_000L, 0L, 1L);
        assertBucket(compareTrend.get(4), "2020-01-03", 0L, PREV_REFUND, 0L);
        assertBucket(compareTrend.get(6), "2020-01-05", 1_000L, 0L, 1L);

        JsonNode yearAgo = fetch(URL + "?from=2020-01-06&to=2020-01-12&compare=YEAR_AGO");
        assertThat(yearAgo.path("compareSummary").isMissingNode()).as("compareSummary 생략(NON_NULL)").isTrue();
        assertThat(yearAgo.path("compareTrend").isMissingNode()).as("compareTrend 생략(NON_NULL)").isTrue();

        JsonNode none = fetch(URL + "?from=2020-01-06&to=2020-01-12");
        assertThat(none.path("compareSummary").isMissingNode()).as("compareSummary 생략(NON_NULL)").isTrue();
        assertThat(none.path("compareTrend").isMissingNode()).as("compareTrend 생략(NON_NULL)").isTrue();
    }

    @Test
    @DisplayName("T6 분해 3축: 금액·비중·주문수·수량·매출 내림차순·compareRevenue(없는 키 null)·drillable")
    void breakdownAxes() throws Exception {
        String query = "?from=2020-01-06&to=2020-01-12&compare=PREVIOUS";
        JsonNode category = fetch(BREAKDOWN_URL + query + "&axis=CATEGORY");
        assertThat(category.get("axis").asText()).isEqualTo("CATEGORY");
        assertThat(category.path("parentKey").isMissingNode()).as("parentKey 생략(NON_NULL)").isTrue();
        assertThat(category.get("totalRevenue").asLong()).isEqualTo(REVENUE);
        JsonNode categoryRows = category.get("rows");
        assertThat(categoryRows).hasSize(2);
        assertRow(categoryRows.get(0), String.valueOf(CATEGORY_B), "통계카테고리B", 35_000L, 77.78, 2L, 4L, 1_000L, true);
        assertRow(categoryRows.get(1), String.valueOf(CATEGORY_A), "통계카테고리A", 10_000L, 22.22, 1L, 1L, 20_000L, true);

        JsonNode sellerRows = fetch(BREAKDOWN_URL + query + "&axis=SELLER").get("rows");
        assertThat(sellerRows).hasSize(2);
        assertRow(sellerRows.get(0), publicId("slr", SELLER_A), "통계셀러A", 30_000L, 66.67, 2L, 3L, 20_000L, true);
        assertRow(sellerRows.get(1), publicId("slr", SELLER_B), "통계셀러B", 15_000L, 33.33, 2L, 2L, 1_000L, true);

        JsonNode productRows = fetch(BREAKDOWN_URL + query + "&axis=PRODUCT").get("rows");
        assertThat(productRows).hasSize(3);
        assertRow(productRows.get(0), publicId("prd", PRODUCT_B), PRODUCT_B_NAME, 20_000L, 44.44, 1L, 2L, null, false);
        assertRow(productRows.get(1), publicId("prd", PRODUCT_C), "통계상품C", 15_000L, 33.33, 2L, 2L, 1_000L, false);
        assertRow(productRows.get(2), publicId("prd", PRODUCT_A), "통계상품A", 10_000L, 22.22, 1L, 1L, 20_000L, false);
        // 상품명은 주문 시점 스냅샷 — 현재 상품명이 바뀌어도 집계 표기는 그대로
        jdbc.update("UPDATE product SET name = ? WHERE id = ?", "이름변경됨", PRODUCT_A);
        JsonNode renamed = fetch(BREAKDOWN_URL + query + "&axis=PRODUCT").get("rows");
        assertThat(renamed.get(2).get("name").asText()).isEqualTo("통계상품A");
    }

    @Test
    @DisplayName("T7 드릴다운: CATEGORY+parentKey→그 카테고리 상품 · SELLER+parentKey→그 셀러 상품 · 미존재 parentKey 빈 rows · PRODUCT+parentKey 400")
    void drilldown() throws Exception {
        String query = "?from=2020-01-06&to=2020-01-12";
        JsonNode category = fetch(BREAKDOWN_URL + query + "&axis=CATEGORY&parentKey=" + CATEGORY_B);
        assertThat(category.get("parentKey").asText()).isEqualTo(String.valueOf(CATEGORY_B));
        assertThat(category.get("totalRevenue").asLong()).isEqualTo(REVENUE);
        JsonNode categoryRows = category.get("rows");
        assertThat(categoryRows).hasSize(2);
        assertRow(categoryRows.get(0), publicId("prd", PRODUCT_B), PRODUCT_B_NAME, 20_000L, 44.44, 1L, 2L, null, false);
        assertRow(categoryRows.get(1), publicId("prd", PRODUCT_C), "통계상품C", 15_000L, 33.33, 2L, 2L, null, false);

        JsonNode sellerRows = fetch(BREAKDOWN_URL + query + "&axis=SELLER&parentKey=" + publicId("slr", SELLER_A))
                .get("rows");
        assertThat(sellerRows).hasSize(2);
        assertRow(sellerRows.get(0), publicId("prd", PRODUCT_B), PRODUCT_B_NAME, 20_000L, 44.44, 1L, 2L, null, false);
        assertRow(sellerRows.get(1), publicId("prd", PRODUCT_A), "통계상품A", 10_000L, 22.22, 1L, 1L, null, false);

        assertThat(fetch(BREAKDOWN_URL + query + "&axis=CATEGORY&parentKey=999999999").get("rows")).isEmpty();
        assertThat(fetch(BREAKDOWN_URL + query + "&axis=SELLER&parentKey=slr_NOPE").get("rows")).isEmpty();

        mockMvc.perform(get(BREAKDOWN_URL + query + "&axis=PRODUCT&parentKey=" + publicId("prd", PRODUCT_A))
                .headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isBadRequest());
        mockMvc.perform(get(BREAKDOWN_URL + query + "&axis=CATEGORY&parentKey=abc")
                .headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T8 400: from>to · 허용 외 unit/compare/axis · 날짜 형식 · from/axis 누락")
    void badRequests() throws Exception {
        for (String url : List.of(
                URL + "?from=2020-01-12&to=2020-01-06",
                URL + "?from=2020-01-06&to=2020-01-12&unit=HOUR",
                URL + "?from=2020-01-06&to=2020-01-12&compare=LAST_MONTH",
                URL + "?from=20200106&to=2020-01-12",
                URL + "?to=2020-01-12",
                BREAKDOWN_URL + "?from=2020-01-06&to=2020-01-12&axis=OPTION",
                BREAKDOWN_URL + "?from=2020-01-06&to=2020-01-12",
                CSV_URL + "?from=2020-01-12&to=2020-01-06&axis=SELLER")) {
            mockMvc.perform(get(url).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("T9 CSV: text/csv;charset=UTF-8 · Content-Disposition filename+filename* · BOM 선두 · 한글 헤더 · 콤마 상품명 이스케이프")
    void csv() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                get(CSV_URL + "?from=2020-01-06&to=2020-01-12&axis=PRODUCT&compare=PREVIOUS")
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn().getResponse();
        String disposition = response.getHeader("Content-Disposition");
        assertThat(disposition).startsWith("attachment; filename=\"sales-breakdown-product-2020-01-06_2020-01-12.csv\"; ");
        assertThat(disposition).contains("filename*=UTF-8''%EB%A7%A4%EC%B6%9C%ED%86%B5%EA%B3%84_%EC%83%81%ED%92%88_2020-01-06_2020-01-12.csv");

        byte[] bytes = response.getContentAsByteArray();
        assertThat(Arrays.copyOf(bytes, UTF8_BOM.length)).isEqualTo(UTF8_BOM);
        String body = new String(bytes, UTF8_BOM.length, bytes.length - UTF8_BOM.length, StandardCharsets.UTF_8);
        String[] lines = body.split("\r\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo("키,이름,매출,비중(%),주문수,수량,비교기간 매출");
        assertThat(lines[1]).isEqualTo(publicId("prd", PRODUCT_B) + ",\"" + PRODUCT_B_NAME + "\",20000,44.44,1,2,");
        assertThat(lines[2]).isEqualTo(publicId("prd", PRODUCT_C) + ",통계상품C,15000,33.33,2,2,1000");
        assertThat(lines[3]).isEqualTo(publicId("prd", PRODUCT_A) + ",통계상품A,10000,22.22,1,1,20000");
    }

    @Test
    @DisplayName("T10 대사: 최근 6개월 unit=MONTH 추이·이번 달 요약이 대시보드(D-180) monthlyRevenue·thisMonth와 일치")
    void reconcileWithDashboard() throws Exception {
        JsonNode dashboard = fetch("/api/v1/admin/dashboard");
        YearMonth thisMonth = YearMonth.now();
        YearMonth firstMonth = thisMonth.minusMonths(5);
        JsonNode stats = fetch(URL + "?from=" + firstMonth.atDay(1) + "&to=" + thisMonth.atEndOfMonth() + "&unit=MONTH");

        JsonNode monthly = dashboard.get("monthlyRevenue");
        JsonNode trend = stats.get("trend");
        assertThat(trend).hasSize(monthly.size());
        for (int index = 0; index < monthly.size(); index++) {
            JsonNode expected = monthly.get(index);
            JsonNode actual = trend.get(index);
            assertThat(actual.get("bucketKey").asText()).isEqualTo(expected.get("yearMonth").asText());
            assertThat(actual.get("revenue").asLong()).isEqualTo(expected.get("revenue").asLong());
            assertThat(actual.get("refund").asLong()).isEqualTo(expected.get("refund").asLong());
            assertThat(actual.get("netRevenue").asLong()).isEqualTo(expected.get("netRevenue").asLong());
            assertThat(actual.get("orderCount").asLong()).isEqualTo(expected.get("orderCount").asLong());
        }
        // 이번 달 시드(12345)가 양쪽 모두에 반영된 상태에서 요약이 일치
        JsonNode dashboardThisMonth = dashboard.get("summary").get("thisMonth");
        JsonNode statsThisMonth = fetch(URL + "?from=" + thisMonth.atDay(1) + "&to=" + thisMonth.atEndOfMonth())
                .get("summary");
        assertThat(dashboardThisMonth.get("revenue").asLong()).isGreaterThanOrEqualTo(12_345L);
        assertThat(statsThisMonth.get("revenue").asLong()).isEqualTo(dashboardThisMonth.get("revenue").asLong());
        assertThat(statsThisMonth.get("refund").asLong()).isEqualTo(dashboardThisMonth.get("refund").asLong());
        assertThat(statsThisMonth.get("orderCount").asLong()).isEqualTo(dashboardThisMonth.get("orderCount").asLong());
    }

    // ---------- helpers ----------

    private JsonNode fetch(String url) throws Exception {
        String body = mockMvc.perform(get(url).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private static List<String> keys(JsonNode trend) {
        return StreamSupport.stream(trend.spliterator(), false)
                .map(node -> node.get("bucketKey").asText()).toList();
    }

    private static void assertBucket(JsonNode bucket, String label, long revenue, long refund, long orderCount) {
        assertThat(bucket.get("bucketLabel").asText()).isEqualTo(label);
        assertThat(bucket.get("revenue").asLong()).as(label + ".revenue").isEqualTo(revenue);
        assertThat(bucket.get("refund").asLong()).as(label + ".refund").isEqualTo(refund);
        assertThat(bucket.get("netRevenue").asLong()).as(label + ".netRevenue").isEqualTo(revenue - refund);
        assertThat(bucket.get("orderCount").asLong()).as(label + ".orderCount").isEqualTo(orderCount);
    }

    private static void assertRow(JsonNode row, String key, String name, long revenue, double share, long orderCount,
            long quantity, Long compareRevenue, boolean drillable) {
        assertThat(row.path("key").asText(null)).isEqualTo(key);
        assertThat(row.get("name").asText()).isEqualTo(name);
        assertThat(row.get("revenue").asLong()).as(name + ".revenue").isEqualTo(revenue);
        assertThat(row.get("share").asDouble()).as(name + ".share").isEqualTo(share);
        assertThat(row.get("orderCount").asLong()).as(name + ".orderCount").isEqualTo(orderCount);
        assertThat(row.get("quantity").asLong()).as(name + ".quantity").isEqualTo(quantity);
        if (compareRevenue == null) {
            assertThat(row.path("compareRevenue").isMissingNode()).as(name + ".compareRevenue 생략(NON_NULL)").isTrue();
        } else {
            assertThat(row.get("compareRevenue").asLong()).as(name + ".compareRevenue").isEqualTo(compareRevenue);
        }
        assertThat(row.get("drillable").asBoolean()).as(name + ".drillable").isEqualTo(drillable);
    }

    private static String publicId(String prefix, long id) {
        return String.format("%s_STAT87%020d", prefix, id);
    }

    // ---------- seed·cleanup(바인딩 파라미터·정적 SQL·SQL injection 위험 없음) ----------

    /**
     * 조회 기간 2020-01-06~12: 주문 3(45,000·품목 수량 5)·COMPLETED 환불 3,000. 직전 7일(2019-12-30~2020-01-05): 주문 2(21,000·수량 3)·환불 2,000.
     * 경계 밖: 2020-01-13 00:00:00(7,000)·미결제(99,999). 이번 달 1일 정오 12,345(대사용).
     */
    private void seed() {
        Long buyerRoleId = jdbc.queryForObject("SELECT id FROM role WHERE code = 'BUYER'", Long.class);
        List<SeedOrder> orders = List.of(
                new SeedOrder(ORDER_START, "PAID", 10_000L, FROM.atStartOfDay()),
                new SeedOrder(ORDER_MID, "PAID", 30_000L, LocalDateTime.of(2020, 1, 8, 12, 0)),
                new SeedOrder(ORDER_LAST_SECOND, "PAID", 5_000L, TO.atTime(23, 59, 59)),
                new SeedOrder(ORDER_AFTER_END, "PAID", 7_000L, TO.plusDays(1).atStartOfDay()),
                new SeedOrder(ORDER_PREV_WEEK, "CONFIRMED", 20_000L, LocalDateTime.of(2019, 12, 30, 10, 0)),
                new SeedOrder(ORDER_BEFORE_START, "PAID", 1_000L, LocalDateTime.of(2020, 1, 5, 23, 59, 59)),
                new SeedOrder(ORDER_PENDING, "PENDING_PAYMENT", 99_999L, null),
                new SeedOrder(ORDER_THIS_MONTH, "PAID", 12_345L, thisMonthNoon));
        List<SeedItem> items = List.of(
                new SeedItem(ID_BASE + 1, ORDER_START, PRODUCT_A, SELLER_A, 1, 10_000L),
                new SeedItem(ITEM_MID_B, ORDER_MID, PRODUCT_B, SELLER_A, 2, 20_000L),
                new SeedItem(ID_BASE + 3, ORDER_MID, PRODUCT_C, SELLER_B, 1, 10_000L),
                new SeedItem(ID_BASE + 4, ORDER_LAST_SECOND, PRODUCT_C, SELLER_B, 1, 5_000L),
                new SeedItem(ITEM_PREV_WEEK, ORDER_PREV_WEEK, PRODUCT_A, SELLER_A, 2, 20_000L),
                new SeedItem(ID_BASE + 6, ORDER_BEFORE_START, PRODUCT_C, SELLER_B, 1, 1_000L),
                new SeedItem(ID_BASE + 7, ORDER_AFTER_END, PRODUCT_A, SELLER_A, 1, 7_000L),
                new SeedItem(ID_BASE + 8, ORDER_PENDING, PRODUCT_A, SELLER_A, 1, 99_999L),
                new SeedItem(ID_BASE + 9, ORDER_THIS_MONTH, PRODUCT_A, SELLER_A, 1, 12_345L));

        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO user (id, public_id, email, name, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        BUYER, publicId("usr", BUYER), "stat87-" + BUYER + "@example.com", "통계구매자");
                jdbc.update("INSERT INTO user_role (id, user_id, role_id, created_at) VALUES (?, ?, ?, NOW(6))", BUYER, BUYER, buyerRoleId);
                insertCategory(CATEGORY_A, "통계카테고리A");
                insertCategory(CATEGORY_B, "통계카테고리B");
                insertSeller(SELLER_A, "통계셀러A");
                insertSeller(SELLER_B, "통계셀러B");
                insertProduct(PRODUCT_A, SELLER_A, CATEGORY_A, "통계상품A");
                insertProduct(PRODUCT_B, SELLER_A, CATEGORY_B, PRODUCT_B_NAME);
                insertProduct(PRODUCT_C, SELLER_B, CATEGORY_B, "통계상품C");
                orders.forEach(this::insertOrder);
                items.forEach(this::insertItem);
                // 환불: 기간 내 COMPLETED 3000 · 직전 주 COMPLETED 2000 · 기간 내 FAILED 500(refunded_at 있어도 미차감·PENDING은 chk_refund_completed_at상 refunded_at NULL)
                insertClaim(CLAIM_MID, ITEM_MID_B, LocalDateTime.of(2020, 1, 9, 9, 0));
                insertClaim(CLAIM_PREV, ITEM_PREV_WEEK, LocalDateTime.of(2020, 1, 2, 9, 0));
                insertRefund(ID_BASE + 1, CLAIM_MID, REFUND, "COMPLETED", LocalDateTime.of(2020, 1, 10, 10, 0));
                insertRefund(ID_BASE + 2, CLAIM_PREV, PREV_REFUND, "COMPLETED", LocalDateTime.of(2020, 1, 3, 10, 0));
                insertRefund(ID_BASE + 3, CLAIM_MID, 500L, "FAILED", LocalDateTime.of(2020, 1, 10, 11, 0));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertCategory(long id, String displayName) {
        jdbc.update("INSERT INTO category (id, parent_id, display_name, depth, sort_order, created_at, updated_at) "
                + "VALUES (?, NULL, ?, 1, ?, NOW(6), NOW(6))", id, displayName, id);
    }

    private void insertSeller(long id, String companyName) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, "
                + "updated_at) VALUES (?, ?, ?, '대표', 'ACTIVE', 1000, NOW(6), NOW(6))", id, publicId("slr", id), companyName);
    }

    private void insertProduct(long id, long sellerId, long categoryId, String name) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, false, NOW(6), NOW(6))",
                id, publicId("prd", id), sellerId, categoryId, name);
    }

    private void insertOrder(SeedOrder order) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                + "shipping_fee, paid_at, ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, NOW(6), NOW(6), NOW(6))",
                order.id(), publicId("ord", order.id()), BUYER, "STAT87-" + order.id(), order.status(), order.total(),
                order.paidAt());
    }

    private void insertItem(SeedItem item) {
        String productName = item.productId() == PRODUCT_A ? "통계상품A" : item.productId() == PRODUCT_B ? PRODUCT_B_NAME : "통계상품C";
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, "
                + "quantity, unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1000, 'PAID', NOW(6), NOW(6))",
                item.id(), publicId("oit", item.id()), item.orderId(), item.productId(), ID_BASE + 1, item.sellerId(),
                productName, item.quantity(), item.total() / item.quantity(), item.total());
    }

    private void insertClaim(long id, long orderItemId, LocalDateTime requestedAt) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, requested_at, "
                + "previous_order_item_status, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'CANCEL', 'CHANGE_MIND', 'COMPLETED', ?, ?, 'PAID', 0, NOW(6), NOW(6))",
                id, publicId("clm", id), orderItemId, BUYER, requestedAt);
    }

    private void insertRefund(long id, long claimId, long amount, String status, LocalDateTime refundedAt) {
        jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, refunded_at, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, publicId("rfn", id), claimId, ID_BASE, amount, status, refundedAt);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM refund WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM category WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM user_role WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM user WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

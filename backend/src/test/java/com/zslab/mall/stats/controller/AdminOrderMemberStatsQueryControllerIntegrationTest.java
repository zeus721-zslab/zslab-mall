package com.zslab.mall.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.StreamSupport;
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
 * 관리자 주문·클레임 / 회원 통계 통합 테스트(Track 88·D-182). Track 87과 같이 다른 테스트가 쓰지 않는 <b>고정 과거 기간(2018-01)</b>에 시드해
 * 격리하고 정확값을 검증한다(매출 통계 테스트의 2019-12~2020-01과도 겹치지 않음). 등급 분포 인원만 전역 집계라 JDBC 재계산과 대사한다.
 *
 * <p>조회 기간 PERIOD = 2018-01-08(월)~2018-01-14(일) = ISO 2018-W02 · 직전 동일 길이 = 2018-01-01~07.
 * 시드 요약(주문): O1 01-08 결제→D1 발송 01-09→배송 01-10→확정 01-12 · O2 01-09 결제 품목 2(I2 배송완료 01-16 기간 밖·I3 취소 클레임 C1 3h·환불 8,000) ·
 * O3 01-14 23:59:59 결제 미발송 · O4 01-15 00:00 결제(경계 밖) · O5 01-13 결제→발송 1h→배송 10h·반품 클레임 C2 12h·환불 9,000 ·
 * O6 01-05(직전) 결제→발송 01-08 72h(종결 시각 기준 표본)·배송 24h·확정 · O7 미결제 · O8 01-03(직전) 결제·취소 클레임 C4 거부(01-04) ·
 * C3 교환 요청(진행 중·reason LEGACY_REASON) · C5 취소 거부(요청 12:00·처리 10:00 역전 → 소요시간 제외).
 */
@AutoConfigureMockMvc
class AdminOrderMemberStatsQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String ORDERS_URL = "/api/v1/admin/stats/orders";
    private static final String MEMBERS_URL = "/api/v1/admin/stats/members";
    private static final String PERIOD = "?from=2018-01-08&to=2018-01-14";
    private static final String EMPTY_PERIOD = "?from=2018-02-01&to=2018-02-07";
    private static final long ADMIN_ID = 988900L;
    private static final long ID_BASE = 988000L;
    private static final long ID_END = 988999L;
    private static final long USER_BUYER_OLD = ID_BASE + 1;      // 2017-12-01 가입·주 구매자
    private static final long USER_NEW_ACTIVE = ID_BASE + 2;     // 2018-01-08 가입
    private static final long USER_NEW_WITHDRAWN = ID_BASE + 3;  // 2018-01-10 가입·01-12 탈퇴
    private static final long USER_OLD_WITHDRAWN = ID_BASE + 4;  // 2017-12-15 가입·01-03 탈퇴
    private static final long USER_AFTER_END = ID_BASE + 5;      // 2018-01-15 00:00 가입(경계 밖)
    private static final long USER_PREV_PERIOD = ID_BASE + 6;    // 2018-01-05 가입·O5 구매자
    private static final long USER_NO_ROLE = ID_BASE + 7;        // 2018-01-09 가입·role 없음
    private static final long SELLER = ID_BASE + 1;
    private static final long PRODUCT = ID_BASE + 1;
    private static final long CATEGORY = ID_BASE + 1;
    private static final long O1 = ID_BASE + 1;
    private static final long O2 = ID_BASE + 2;
    private static final long O3 = ID_BASE + 3;
    private static final long O4 = ID_BASE + 4;
    private static final long O5 = ID_BASE + 5;
    private static final long O6 = ID_BASE + 6;
    private static final long O7 = ID_BASE + 7;
    private static final long O8 = ID_BASE + 8;
    private static final long I1 = ID_BASE + 1;
    private static final long I2 = ID_BASE + 2;
    private static final long I3 = ID_BASE + 3;
    private static final long I4 = ID_BASE + 4;
    private static final long I6 = ID_BASE + 6;
    private static final long I7 = ID_BASE + 7;
    private static final long I8 = ID_BASE + 8;
    private static final long I9 = ID_BASE + 9;
    private static final long I5 = ID_BASE + 5;
    private static final long C1 = ID_BASE + 1;
    private static final long C2 = ID_BASE + 2;
    private static final long C3 = ID_BASE + 3;
    private static final long C4 = ID_BASE + 4;
    private static final long C5 = ID_BASE + 5;
    private static final String LEGACY_REASON = "LEGACY_REASON";
    private static final double TOLERANCE = 0.011;

    private record SeedOrder(long id, long buyerId, String status, long total, LocalDateTime paidAt) {
    }

    private record SeedItem(long id, long orderId, long total, String status, LocalDateTime confirmedAt) {
    }

    private record SeedDelivery(long id, long orderItemId, LocalDateTime shippedAt, LocalDateTime deliveredAt) {
    }

    private record SeedClaim(long id, long orderItemId, String type, String reasonCode, String status,
            LocalDateTime requestedAt, LocalDateTime processedAt) {
    }

    private record SeedUser(long id, LocalDateTime createdAt, LocalDateTime withdrawnAt, boolean buyer, String grade) {
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

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인가: 비인증 401 · BUYER 403 · ADMIN 200(2 엔드포인트)")
    void authorization() throws Exception {
        for (String url : List.of(ORDERS_URL, MEMBERS_URL)) {
            mockMvc.perform(get(url + PERIOD)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(url + PERIOD).headers(authHeaders.buyer(USER_BUYER_OLD))).andExpect(status().isForbidden());
            mockMvc.perform(get(url + PERIOD).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("T2 퍼널 정합: 코호트 5·각 단계가 앞 단계의 부분집합·취소/반품 종결 건수·경계(01-15 00:00 제외)")
    void funnelConsistency() throws Exception {
        JsonNode funnel = fetch(ORDERS_URL + PERIOD).get("funnel");
        assertThat(funnel.fieldNames()).toIterable().containsExactlyInAnyOrder("paidItems", "shippedItems", "deliveredItems",
                "confirmedItems", "cancelledItems", "returnedItems");
        long paid = funnel.get("paidItems").asLong();
        long shipped = funnel.get("shippedItems").asLong();
        long delivered = funnel.get("deliveredItems").asLong();
        long confirmed = funnel.get("confirmedItems").asLong();
        assertThat(paid).isEqualTo(5);
        assertThat(shipped).isEqualTo(3);
        assertThat(delivered).isEqualTo(3);
        assertThat(confirmed).isEqualTo(1);
        assertThat(funnel.get("cancelledItems").asLong()).isEqualTo(1);
        assertThat(funnel.get("returnedItems").asLong()).isEqualTo(1);
        // 단계 역행 없음(부분집합)·종결 이탈은 코호트 안
        assertThat(shipped).isLessThanOrEqualTo(paid);
        assertThat(delivered).isLessThanOrEqualTo(shipped);
        assertThat(confirmed).isLessThanOrEqualTo(delivered);
        assertThat(funnel.get("cancelledItems").asLong() + funnel.get("returnedItems").asLong()).isLessThanOrEqualTo(paid);
        // to=01-15면 O4 품목이 코호트에 들어온다(경계)
        assertThat(fetch(ORDERS_URL + "?from=2018-01-08&to=2018-01-15").get("funnel").get("paidItems").asLong()).isEqualTo(6);
    }

    @Test
    @DisplayName("T3 퍼널 코호트 귀속: 기간 내 결제됐으나 기간 밖(01-16)에 배송완료된 품목도 delivered에 포함·직전 기간 결제 O6은 코호트 밖")
    void funnelCohortAttribution() throws Exception {
        JsonNode funnel = fetch(ORDERS_URL + PERIOD).get("funnel");
        // I1(01-10)·I2(01-16 기간 밖)·I6(01-13) = 3. O6(01-05 결제·01-09 배송완료)은 코호트 밖이라 미포함
        assertThat(funnel.get("deliveredItems").asLong()).isEqualTo(3);
        JsonNode previous = fetch(ORDERS_URL + "?from=2018-01-01&to=2018-01-07").get("funnel");
        assertThat(previous.get("paidItems").asLong()).isEqualTo(2);
        assertThat(previous.get("shippedItems").asLong()).as("O6은 01-08 발송이지만 코호트 도달로 집계").isEqualTo(1);
        assertThat(previous.get("confirmedItems").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("T4 소요시간: 종결 시각 기준 표본·짝수 중앙값(24,48 평균)·홀수 중앙값·역전 건 제외·표본 0이면 null")
    void leadTime() throws Exception {
        JsonNode leadTime = fetch(ORDERS_URL + PERIOD).get("leadTime");
        assertThat(leadTime.fieldNames()).toIterable().containsExactlyInAnyOrder("paidToShipped", "shippedToDelivered",
                "claimRequestedToClosed");
        // 결제→발송: D1 24h·D2 48h·D5 1h·D6 72h(01-05 결제·01-08 발송·종결 시각 기준 포함)·D4(01-15 발송) 제외
        assertMetric(leadTime.get("paidToShipped"), 36.25, 36.0, 4);
        // 발송→배송완료: D1 24h·D5 10h·D6 24h·D2(01-16 배송) 제외
        assertMetric(leadTime.get("shippedToDelivered"), 19.33, 24.0, 3);
        // 클레임 요청→종결: C1 3h·C2 12h·C4(01-04 종결) 제외·C3(진행 중) 제외·C5(역전) 제외
        assertMetric(leadTime.get("claimRequestedToClosed"), 7.5, 7.5, 2);

        JsonNode empty = fetch(ORDERS_URL + EMPTY_PERIOD);
        assertThat(empty.get("leadTime").fieldNames()).toIterable().as("표본 0 → 구간 null 생략").isEmpty();
        assertThat(empty.get("funnel").get("paidItems").asLong()).isZero();
    }

    @Test
    @DisplayName("T5 클레임 요약: 클레임률 4/5=80%·환불률 금액 17,000/44,000=38.64%·환불 건수 2·키 집합")
    void claimSummary() throws Exception {
        JsonNode summary = fetch(ORDERS_URL + PERIOD).get("claimSummary");
        assertThat(summary.fieldNames()).toIterable().containsExactlyInAnyOrder("claimCount", "claimRate", "refundAmount",
                "refundRate", "refundCount", "paidItemCount");
        assertThat(summary.get("claimCount").asLong()).isEqualTo(4);
        assertThat(summary.get("paidItemCount").asLong()).isEqualTo(5);
        assertThat(summary.get("claimRate").asDouble()).isEqualTo(80.0);
        assertThat(summary.get("refundAmount").asLong()).isEqualTo(17_000L);
        assertThat(summary.get("refundCount").asLong()).isEqualTo(2);
        assertThat(summary.get("refundRate").asDouble()).isEqualTo(38.64);

        JsonNode trend = fetch(ORDERS_URL + PERIOD + "&unit=DAY").get("claimTrend");
        assertThat(trend).hasSize(7);
        assertThat(keys(trend)).containsExactly("2018-01-08", "2018-01-09", "2018-01-10", "2018-01-11", "2018-01-12",
                "2018-01-13", "2018-01-14");
        assertThat(trend.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("bucketKey", "bucketLabel", "claimCount",
                "claimRate", "refundAmount", "refundRate", "refundCount");
        assertTrendBucket(trend.get(1), 1, 50.0, 0, 0.0, 0);        // 01-09: C1 요청·품목 2
        assertTrendBucket(trend.get(2), 0, 0.0, 8_000L, 0.0, 1);   // 01-10: 환불 8,000·매출 0 → 환불률 0
        assertTrendBucket(trend.get(3), 0, 0.0, 0, 0.0, 0);        // 01-11: 빈 구간
        assertTrendBucket(trend.get(6), 1, 100.0, 9_000L, 180.0, 1); // 01-14: C2 요청·품목 1·환불 9,000/매출 5,000

        JsonNode weekly = fetch(ORDERS_URL + "?from=2018-01-01&to=2018-01-14&unit=WEEK").get("claimTrend");
        assertThat(keys(weekly)).containsExactly("2018-W01", "2018-W02");
        assertThat(weekly.get(0).get("bucketLabel").asText()).isEqualTo("2018-01-01");
        assertTrendBucket(weekly.get(1), 4, 80.0, 17_000L, 38.64, 2);
    }

    @Test
    @DisplayName("T6 유형·사유 분포: 합계 = 전체 건수·share 합 ≈ 100·건수 내림차순·enum 외 reason_code 원문 반환")
    void claimDistribution() throws Exception {
        JsonNode response = fetch(ORDERS_URL + PERIOD);
        JsonNode byType = response.get("claimByType");
        assertThat(byType).hasSize(3);
        assertThat(byType.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("type", "count", "share");
        assertThat(byType.get(0).get("type").asText()).isEqualTo("CANCEL");
        assertThat(byType.get(0).get("count").asLong()).isEqualTo(2);
        assertThat(byType.get(0).get("share").asDouble()).isEqualTo(50.0);
        assertThat(sum(byType, "count")).isEqualTo(4);
        assertThat(sumDouble(byType, "share")).isCloseTo(100.0, org.assertj.core.data.Offset.offset(TOLERANCE));

        JsonNode byReason = response.get("claimByReason");
        assertThat(byReason).hasSize(3);
        assertThat(byReason.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("reasonCode", "count", "share");
        assertThat(StreamSupport.stream(byReason.spliterator(), false).map(node -> node.get("reasonCode").asText()).toList())
                .containsExactly("BUYER_CHANGED_MIND", LEGACY_REASON, "PRODUCT_DEFECT");
        assertThat(byReason.get(1).get("count").asLong()).isEqualTo(1);
        assertThat(sum(byReason, "count")).isEqualTo(4);
        assertThat(sumDouble(byReason, "share")).isCloseTo(100.0, org.assertj.core.data.Offset.offset(TOLERANCE));
    }

    @Test
    @DisplayName("T7 주문 통계 비교 기간: 요약·추이에만 적용(PREVIOUS)·YEAR_AGO/NONE 생략·최상위 키 집합")
    void orderStatsCompare() throws Exception {
        JsonNode previous = fetch(ORDERS_URL + PERIOD + "&compare=PREVIOUS");
        assertThat(previous.fieldNames()).toIterable().containsExactlyInAnyOrder("funnel", "leadTime", "claimSummary",
                "compareClaimSummary", "claimTrend", "compareClaimTrend", "claimByType", "claimByReason");
        JsonNode compareSummary = previous.get("compareClaimSummary");
        assertThat(compareSummary.get("claimCount").asLong()).isEqualTo(1);
        assertThat(compareSummary.get("paidItemCount").asLong()).isEqualTo(2);
        assertThat(compareSummary.get("claimRate").asDouble()).isEqualTo(50.0);
        assertThat(compareSummary.get("refundAmount").asLong()).isZero();
        assertThat(compareSummary.get("refundCount").asLong()).isZero();
        JsonNode compareTrend = previous.get("compareClaimTrend");
        assertThat(compareTrend).hasSize(7);
        assertThat(keys(compareTrend).get(0)).isEqualTo("2018-01-01");
        assertTrendBucket(compareTrend.get(2), 1, 100.0, 0, 0.0, 0); // 01-03: C4 요청·O8 품목 1

        JsonNode yearAgo = fetch(ORDERS_URL + PERIOD + "&compare=YEAR_AGO");
        assertThat(yearAgo.fieldNames()).toIterable().containsExactlyInAnyOrder("funnel", "leadTime", "claimSummary", "claimTrend",
                "claimByType", "claimByReason");
        JsonNode none = fetch(ORDERS_URL + PERIOD);
        assertThat(none.path("compareClaimSummary").isMissingNode()).isTrue();
        assertThat(none.path("compareClaimTrend").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("T8 회원: 신규 2·탈퇴 1·활성 누적이 기간 시작 기준값(2)에서 이어져 종료 3·role 없는 가입자 제외·경계")
    void memberSignupAndCumulative() throws Exception {
        JsonNode response = fetch(MEMBERS_URL + PERIOD + "&unit=DAY");
        JsonNode summary = response.get("summary");
        assertThat(summary.fieldNames()).toIterable().containsExactlyInAnyOrder("newCount", "withdrawnCount", "activeTotal",
                "repurchaseRate", "buyerCount", "repeatBuyerCount");
        assertThat(summary.get("newCount").asLong()).isEqualTo(2);
        assertThat(summary.get("withdrawnCount").asLong()).isEqualTo(1);
        assertThat(summary.get("activeTotal").asLong()).isEqualTo(3);

        JsonNode trend = response.get("signupTrend");
        assertThat(trend).hasSize(7);
        assertThat(trend.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("bucketKey", "bucketLabel", "newCount",
                "activeCumulative");
        assertSignup(trend.get(0), "2018-01-08", 1, 3); // 기준 2(USER_BUYER_OLD·USER_PREV_PERIOD) + 가입 1
        assertSignup(trend.get(1), "2018-01-09", 0, 3); // role 없는 가입자 제외
        assertSignup(trend.get(2), "2018-01-10", 1, 4);
        assertSignup(trend.get(4), "2018-01-12", 0, 3); // 탈퇴 1
        assertSignup(trend.get(6), "2018-01-14", 0, 3);
        assertThat(trend.get(6).get("activeCumulative").asLong()).isEqualTo(summary.get("activeTotal").asLong());
        // 01-15 00:00 가입자는 to=01-15면 포함
        assertThat(fetch(MEMBERS_URL + "?from=2018-01-08&to=2018-01-15").get("summary").get("newCount").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("T9 재구매율 50%·1회/재구매 매출 분리·상위 회원 정렬·이름·이메일 원문")
    void repurchaseAndTopBuyers() throws Exception {
        JsonNode response = fetch(MEMBERS_URL + PERIOD);
        JsonNode summary = response.get("summary");
        assertThat(summary.get("buyerCount").asLong()).isEqualTo(2);
        assertThat(summary.get("repeatBuyerCount").asLong()).isEqualTo(1);
        assertThat(summary.get("repurchaseRate").asDouble()).isEqualTo(50.0);

        JsonNode split = response.get("buyerSplit");
        assertThat(split.fieldNames()).toIterable().containsExactlyInAnyOrder("firstTimeBuyerCount", "firstTimeRevenue",
                "repeatBuyerCount", "repeatRevenue");
        assertThat(split.get("firstTimeBuyerCount").asLong()).isEqualTo(1);
        assertThat(split.get("firstTimeRevenue").asLong()).isEqualTo(9_000L);
        assertThat(split.get("repeatBuyerCount").asLong()).isEqualTo(1);
        assertThat(split.get("repeatRevenue").asLong()).isEqualTo(35_000L);

        JsonNode top = response.get("topBuyers");
        assertThat(top).hasSize(2);
        assertThat(top.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("userPublicId", "name", "email", "orderCount",
                "revenue");
        assertThat(top.get(0).get("userPublicId").asText()).isEqualTo(publicId("usr", USER_BUYER_OLD));
        assertThat(top.get(0).get("name").asText()).isEqualTo("통계회원" + USER_BUYER_OLD);
        assertThat(top.get(0).get("email").asText()).isEqualTo("stat88-" + USER_BUYER_OLD + "@example.com");
        assertThat(top.get(0).get("orderCount").asLong()).isEqualTo(3);
        assertThat(top.get(0).get("revenue").asLong()).isEqualTo(35_000L);
        assertThat(top.get(1).get("userPublicId").asText()).isEqualTo(publicId("usr", USER_PREV_PERIOD));
        assertThat(top.get(1).get("revenue").asLong()).isEqualTo(9_000L);
    }

    @Test
    @DisplayName("T10 등급 분포: 3등급 고정·인원은 활성 회원 JDBC 재계산과 일치·매출 GOLD 35,000/SILVER 9,000·share 합 ≈ 100")
    void gradeDistribution() throws Exception {
        JsonNode grades = fetch(MEMBERS_URL + PERIOD).get("gradeDistribution");
        assertThat(grades).hasSize(3);
        assertThat(grades.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("gradeCode", "memberCount", "share",
                "revenue", "revenueShare");
        assertThat(StreamSupport.stream(grades.spliterator(), false).map(node -> node.get("gradeCode").asText()).toList())
                .containsExactly("SILVER", "GOLD", "PLATINUM");
        for (JsonNode grade : grades) {
            Long expected = jdbc.queryForObject("SELECT COUNT(*) FROM buyer_profile bp JOIN user u ON u.id = bp.user_id "
                    + "JOIN buyer_grade g ON g.id = bp.grade_id WHERE u.withdrawn_at IS NULL AND u.deleted_at IS NULL AND g.code = ?",
                    Long.class, grade.get("gradeCode").asText());
            assertThat(grade.get("memberCount").asLong()).as(grade.get("gradeCode").asText()).isEqualTo(expected);
        }
        assertThat(sumDouble(grades, "share")).isCloseTo(100.0, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(grades.get(0).get("revenue").asLong()).isEqualTo(9_000L);
        assertThat(grades.get(0).get("revenueShare").asDouble()).isEqualTo(20.45);
        assertThat(grades.get(1).get("revenue").asLong()).isEqualTo(35_000L);
        assertThat(grades.get(1).get("revenueShare").asDouble()).isEqualTo(79.55);
        assertThat(grades.get(2).get("revenue").asLong()).isZero();
        assertThat(grades.get(2).get("revenueShare").asDouble()).isZero();
    }

    @Test
    @DisplayName("T11 회원 통계 비교 기간: summary·signupTrend에만 적용·분포/분리/상위에는 비교 필드 없음·YEAR_AGO 생략")
    void memberStatsCompare() throws Exception {
        JsonNode previous = fetch(MEMBERS_URL + PERIOD + "&compare=PREVIOUS");
        assertThat(previous.fieldNames()).toIterable().containsExactlyInAnyOrder("summary", "compareSummary", "signupTrend",
                "compareSignupTrend", "gradeDistribution", "buyerSplit", "topBuyers");
        JsonNode compareSummary = previous.get("compareSummary");
        assertThat(compareSummary.get("newCount").asLong()).isEqualTo(1);      // USER_PREV_PERIOD 01-05
        assertThat(compareSummary.get("withdrawnCount").asLong()).isEqualTo(1); // USER_OLD_WITHDRAWN 01-03
        assertThat(compareSummary.get("activeTotal").asLong()).isEqualTo(2);
        assertThat(compareSummary.get("buyerCount").asLong()).isEqualTo(1);     // O6·O8 = USER_BUYER_OLD 2회
        assertThat(compareSummary.get("repeatBuyerCount").asLong()).isEqualTo(1);
        assertThat(compareSummary.get("repurchaseRate").asDouble()).isEqualTo(100.0);
        JsonNode compareTrend = previous.get("compareSignupTrend");
        assertThat(compareTrend).hasSize(7);
        assertSignup(compareTrend.get(0), "2018-01-01", 0, 2); // 기준: 가입 2(OLD·OLD_WITHDRAWN) − 탈퇴 0
        assertSignup(compareTrend.get(2), "2018-01-03", 0, 1); // 탈퇴
        assertSignup(compareTrend.get(4), "2018-01-05", 1, 2);

        JsonNode yearAgo = fetch(MEMBERS_URL + PERIOD + "&compare=YEAR_AGO");
        assertThat(yearAgo.fieldNames()).toIterable().containsExactlyInAnyOrder("summary", "signupTrend", "gradeDistribution",
                "buyerSplit", "topBuyers");
    }

    @Test
    @DisplayName("T12 400: from>to · 허용 외 unit/compare · 날짜 형식 · 필수 누락(2 엔드포인트)")
    void badRequests() throws Exception {
        for (String url : List.of(ORDERS_URL, MEMBERS_URL)) {
            mockMvc.perform(get(url + "?from=2018-01-14&to=2018-01-08").headers(authHeaders.admin(ADMIN_ID)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(url + PERIOD + "&unit=HOUR").headers(authHeaders.admin(ADMIN_ID)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(url + PERIOD + "&compare=LAST_MONTH").headers(authHeaders.admin(ADMIN_ID)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(url + "?from=2018/01/08&to=2018-01-14").headers(authHeaders.admin(ADMIN_ID)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(url + "?from=2018-01-08").headers(authHeaders.admin(ADMIN_ID)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------- helpers ----------

    private JsonNode fetch(String url) throws Exception {
        String body = mockMvc.perform(get(url).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private static List<String> keys(JsonNode trend) {
        return StreamSupport.stream(trend.spliterator(), false).map(node -> node.get("bucketKey").asText()).toList();
    }

    private static long sum(JsonNode rows, String field) {
        return StreamSupport.stream(rows.spliterator(), false).mapToLong(node -> node.get(field).asLong()).sum();
    }

    private static double sumDouble(JsonNode rows, String field) {
        return StreamSupport.stream(rows.spliterator(), false).mapToDouble(node -> node.get(field).asDouble()).sum();
    }

    private static void assertMetric(JsonNode metric, double avgHours, double medianHours, long count) {
        assertThat(metric.fieldNames()).toIterable().containsExactlyInAnyOrder("avgHours", "medianHours", "count");
        assertThat(metric.get("avgHours").asDouble()).isEqualTo(avgHours);
        assertThat(metric.get("medianHours").asDouble()).isEqualTo(medianHours);
        assertThat(metric.get("count").asLong()).isEqualTo(count);
    }

    private static void assertTrendBucket(JsonNode bucket, long claimCount, double claimRate, long refundAmount,
            double refundRate, long refundCount) {
        String label = bucket.get("bucketLabel").asText();
        assertThat(bucket.get("claimCount").asLong()).as(label + ".claimCount").isEqualTo(claimCount);
        assertThat(bucket.get("claimRate").asDouble()).as(label + ".claimRate").isEqualTo(claimRate);
        assertThat(bucket.get("refundAmount").asLong()).as(label + ".refundAmount").isEqualTo(refundAmount);
        assertThat(bucket.get("refundRate").asDouble()).as(label + ".refundRate").isEqualTo(refundRate);
        assertThat(bucket.get("refundCount").asLong()).as(label + ".refundCount").isEqualTo(refundCount);
    }

    private static void assertSignup(JsonNode bucket, String label, long newCount, long activeCumulative) {
        assertThat(bucket.get("bucketLabel").asText()).isEqualTo(label);
        assertThat(bucket.get("newCount").asLong()).as(label + ".newCount").isEqualTo(newCount);
        assertThat(bucket.get("activeCumulative").asLong()).as(label + ".activeCumulative").isEqualTo(activeCumulative);
    }

    private static String publicId(String prefix, long id) {
        return String.format("%s_STAT88%020d", prefix, id);
    }

    private static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2018, 1, day, hour, 0);
    }

    // ---------- seed·cleanup(바인딩 파라미터·정적 SQL·SQL injection 위험 없음) ----------

    private void seed() {
        Long buyerRoleId = jdbc.queryForObject("SELECT id FROM role WHERE code = 'BUYER'", Long.class);
        List<SeedUser> users = List.of(
                new SeedUser(USER_BUYER_OLD, LocalDateTime.of(2017, 12, 1, 9, 0), null, true, "GOLD"),
                new SeedUser(USER_NEW_ACTIVE, at(8, 10), null, true, "SILVER"),
                new SeedUser(USER_NEW_WITHDRAWN, at(10, 10), at(12, 10), true, "PLATINUM"),
                new SeedUser(USER_OLD_WITHDRAWN, LocalDateTime.of(2017, 12, 15, 9, 0), at(3, 10), true, "SILVER"),
                new SeedUser(USER_AFTER_END, at(15, 0), null, true, "SILVER"),
                new SeedUser(USER_PREV_PERIOD, at(5, 10), null, true, "SILVER"),
                new SeedUser(USER_NO_ROLE, at(9, 10), null, false, null));
        List<SeedOrder> orders = List.of(
                new SeedOrder(O1, USER_BUYER_OLD, "CONFIRMED", 10_000L, at(8, 10)),
                new SeedOrder(O2, USER_BUYER_OLD, "PARTIAL_CANCEL", 20_000L, at(9, 12)),
                new SeedOrder(O3, USER_BUYER_OLD, "PAID", 5_000L, LocalDateTime.of(2018, 1, 14, 23, 59, 59)),
                new SeedOrder(O4, USER_BUYER_OLD, "SHIPPING", 7_000L, at(15, 0)),
                new SeedOrder(O5, USER_PREV_PERIOD, "CONFIRMED", 9_000L, at(13, 9)),
                new SeedOrder(O6, USER_BUYER_OLD, "CONFIRMED", 21_000L, at(5, 9)),
                new SeedOrder(O7, USER_BUYER_OLD, "PENDING_PAYMENT", 99_999L, null),
                new SeedOrder(O8, USER_BUYER_OLD, "CANCELLED", 4_000L, at(3, 9)));
        List<SeedItem> items = List.of(
                new SeedItem(I1, O1, 10_000L, "CONFIRMED", at(12, 10)),
                new SeedItem(I2, O2, 12_000L, "DELIVERED", null),
                new SeedItem(I3, O2, 8_000L, "CANCELLED", null),
                new SeedItem(I4, O3, 5_000L, "PAID", null),
                new SeedItem(I5, O4, 7_000L, "SHIPPING", null),
                new SeedItem(I6, O5, 9_000L, "RETURNED", null),
                new SeedItem(I7, O6, 21_000L, "CONFIRMED", at(11, 9)),
                new SeedItem(I8, O7, 99_999L, "ORDERED", null),
                new SeedItem(I9, O8, 4_000L, "CANCELLED", null));
        List<SeedDelivery> deliveries = List.of(
                new SeedDelivery(ID_BASE + 1, I1, at(9, 10), at(10, 10)),
                new SeedDelivery(ID_BASE + 2, I2, at(11, 12), at(16, 12)),
                new SeedDelivery(ID_BASE + 4, I5, at(15, 1), null),
                new SeedDelivery(ID_BASE + 5, I6, at(13, 10), at(13, 20)),
                new SeedDelivery(ID_BASE + 6, I7, at(8, 9), at(9, 9)));
        List<SeedClaim> claims = List.of(
                new SeedClaim(C1, I3, "CANCEL", "BUYER_CHANGED_MIND", "COMPLETED", at(9, 13), at(9, 16)),
                new SeedClaim(C2, I6, "RETURN", "PRODUCT_DEFECT", "COMPLETED", at(14, 9), at(14, 21)),
                new SeedClaim(C3, I2, "EXCHANGE", LEGACY_REASON, "REQUESTED", at(12, 10), null),
                new SeedClaim(C4, I9, "CANCEL", "ORDER_MISTAKE", "REJECTED", at(3, 10), at(4, 10)),
                new SeedClaim(C5, I4, "CANCEL", "BUYER_CHANGED_MIND", "REJECTED", at(13, 12), at(13, 10)));

        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (SeedUser user : users) {
                    insertUser(user, buyerRoleId);
                }
                jdbc.update("INSERT INTO category (id, parent_id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 1, ?, NOW(6), NOW(6))", CATEGORY, "통계88카테고리", CATEGORY);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, "
                        + "updated_at) VALUES (?, ?, ?, '대표', 'ACTIVE', 1000, NOW(6), NOW(6))", SELLER, publicId("slr", SELLER), "통계88셀러");
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, false, NOW(6), NOW(6))",
                        PRODUCT, publicId("prd", PRODUCT), SELLER, CATEGORY, "통계88상품");
                orders.forEach(this::insertOrder);
                items.forEach(this::insertItem);
                deliveries.forEach(this::insertDelivery);
                // 반품 회수(RETURN·claim_id) 1행: 원 발송 필터(OUTBOUND·claim_id NULL)에 걸리면 안 된다
                jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                        + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, 'RETURN', 'CJ', ?, 'DELIVERED', ?, ?, ?, NOW(6), NOW(6))",
                        ID_BASE + 7, publicId("dlv", ID_BASE + 7), I6, "STAT88-RET", at(14, 10), at(14, 20), C2);
                claims.forEach(this::insertClaim);
                insertRefund(ID_BASE + 1, C1, 8_000L, "COMPLETED", at(10, 9));
                insertRefund(ID_BASE + 2, C2, 9_000L, "COMPLETED", at(14, 22));
                insertRefund(ID_BASE + 3, C1, 500L, "FAILED", at(10, 11));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertUser(SeedUser user, long buyerRoleId) {
        jdbc.update("INSERT INTO user (id, public_id, email, name, withdrawn_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(6))",
                user.id(), publicId("usr", user.id()), "stat88-" + user.id() + "@example.com", "통계회원" + user.id(),
                user.withdrawnAt(), user.createdAt());
        if (!user.buyer()) {
            return;
        }
        jdbc.update("INSERT INTO user_role (id, user_id, role_id, created_at) VALUES (?, ?, ?, NOW(6))", user.id(), user.id(), buyerRoleId);
        Long gradeId = jdbc.queryForObject("SELECT id FROM buyer_grade WHERE code = ?", Long.class, user.grade());
        jdbc.update("INSERT INTO buyer_profile (user_id, grade_id, grade_source, grade_updated_at, created_at, updated_at) "
                + "VALUES (?, ?, 'AUTO', NULL, NOW(6), NOW(6))", user.id(), gradeId);
    }

    private void insertOrder(SeedOrder order) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                + "shipping_fee, paid_at, ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, NOW(6), NOW(6), NOW(6))",
                order.id(), publicId("ord", order.id()), order.buyerId(), "STAT88-" + order.id(), order.status(), order.total(),
                order.paidAt());
    }

    private void insertItem(SeedItem item) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, "
                + "quantity, unit_price, total_price, commission_rate, item_status, confirmed_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 1000, ?, ?, NOW(6), NOW(6))",
                item.id(), publicId("oit", item.id()), item.orderId(), PRODUCT, ID_BASE + 1, SELLER, "통계88상품",
                item.total(), item.total(), item.status(), item.confirmedAt());
    }

    private void insertDelivery(SeedDelivery delivery) {
        String status = delivery.deliveredAt() == null ? "SHIPPING" : "DELIVERED";
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, ?, ?, ?, NULL, NOW(6), NOW(6))",
                delivery.id(), publicId("dlv", delivery.id()), delivery.orderItemId(), "STAT88-" + delivery.id(), status,
                delivery.shippedAt(), delivery.deliveredAt());
    }

    private void insertClaim(SeedClaim claim) {
        String rejectReason = "REJECTED".equals(claim.status()) ? "OTHER" : null;
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, requested_at, "
                + "processed_at, reject_reason_code, previous_order_item_status, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PAID', 0, NOW(6), NOW(6))",
                claim.id(), publicId("clm", claim.id()), claim.orderItemId(), claim.type(), claim.reasonCode(), claim.status(),
                USER_BUYER_OLD, claim.requestedAt(), claim.processedAt(), rejectReason);
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
                jdbc.update("DELETE FROM delivery WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM category WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM buyer_profile WHERE user_id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM user_role WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM user WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

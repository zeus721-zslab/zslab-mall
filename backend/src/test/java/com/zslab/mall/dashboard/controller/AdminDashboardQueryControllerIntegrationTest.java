package com.zslab.mall.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.SellerGrossProjection;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 * 관리자 대시보드 통합 테스트(Track 86·D-180). 집계는 DB 전역이라 다른 테스트의 잔여 행에 영향받지 않도록 시드 전·후 응답의
 * <b>차분</b>으로 검증한다(요약·처리 대기·추이). 목록(최근·상위)은 시드 행이 최신·최대라 직접 검증한다.
 *
 * <p>시드 시각은 서비스와 같은 JVM 기본 시간대(KST)의 오늘을 기준으로 잡는다. 30일 경계 주문(29·31일 전)은 실행일에 따라 이번 달·전월
 * 어디에든 떨어지므로 기대값은 시드 목록에서 기간 조건으로 직접 합산한다(단순 산술·서비스 로직 재구현 아님).
 */
@AutoConfigureMockMvc
class AdminDashboardQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/dashboard";
    private static final long ADMIN_ID = 986900L;
    private static final long ID_BASE = 986000L;
    private static final long ID_END = 986999L;
    private static final long BUYER_TODAY = ID_BASE + 1;
    private static final long BUYER_YESTERDAY = ID_BASE + 2;
    private static final long BUYER_PREV_MONTH = ID_BASE + 3;
    private static final long NON_BUYER_TODAY = ID_BASE + 4;
    private static final long SELLER_A = ID_BASE + 1;
    private static final long SELLER_B = ID_BASE + 2;
    private static final long SELLER_PENDING = ID_BASE + 3;
    private static final long SELLER_PENDING_DELETED = ID_BASE + 4;
    private static final long PRODUCT_A = ID_BASE + 1;
    private static final long PRODUCT_B = ID_BASE + 2;
    private static final long PRODUCT_SOLDOUT = ID_BASE + 3;
    private static final long PRODUCT_PENDING = ID_BASE + 4;
    private static final long PRODUCT_PENDING_DELETED = ID_BASE + 5;
    private static final long ORDER_TODAY_MIDNIGHT = ID_BASE + 1;
    private static final long ORDER_TODAY_NOON = ID_BASE + 2;
    private static final long ORDER_YESTERDAY_LAST_SECOND = ID_BASE + 3;
    private static final long ORDER_PREV_MONTH = ID_BASE + 4;
    private static final long ORDER_PENDING_PAYMENT = ID_BASE + 5;
    private static final long ORDER_31_DAYS_AGO = ID_BASE + 6;
    private static final long ORDER_29_DAYS_AGO = ID_BASE + 7;
    private static final long ITEM_YESTERDAY = ID_BASE + 4;
    private static final long ITEM_PREV_MONTH = ID_BASE + 5;
    private static final long CLAIM_REQUESTED = ID_BASE + 1;
    private static final long CLAIM_APPROVED = ID_BASE + 2;
    private static final long REFUND_TODAY_AMOUNT = 3_000L;
    private static final long REFUND_PREV_MONTH_AMOUNT = 4_000L;
    private static final int TREND_MONTHS = 6;
    private static final int TREND_DAYS = 30;
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 시드 주문(결제 시각·총액). paidAt null = 미결제. */
    private record SeedOrder(long id, long buyerId, String status, long total, LocalDateTime paidAt) {
    }

    /** 시드 품목. confirmedAt은 CONFIRMED 품목만 값이 있으며 결제 시각과 같게 둔다(정산 대사 단순화). */
    private record SeedItem(long id, long orderId, long productId, long sellerId, int quantity, long total,
            String itemStatus, LocalDateTime confirmedAt) {
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
    @Autowired
    private OrderItemRepository orderItemRepository;

    private TransactionTemplate tx;
    private LocalDate today;
    private YearMonth thisMonth;
    private YearMonth previousMonth;
    private LocalDateTime todayMidnight;
    private LocalDateTime todayNoon;
    private LocalDateTime yesterdayLastSecond;
    private LocalDateTime previousMonthMid;
    private List<SeedOrder> orders;
    private List<SeedItem> items;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        today = LocalDate.now();
        thisMonth = YearMonth.from(today);
        previousMonth = thisMonth.minusMonths(1);
        todayMidnight = today.atStartOfDay();
        todayNoon = today.atTime(12, 0);
        yesterdayLastSecond = today.minusDays(1).atTime(23, 59, 59);
        previousMonthMid = previousMonth.atDay(15).atTime(10, 0);
        LocalDateTime daysAgo31 = today.minusDays(31).atTime(9, 0);
        LocalDateTime daysAgo29 = today.minusDays(29).atStartOfDay();

        orders = List.of(
                new SeedOrder(ORDER_TODAY_MIDNIGHT, BUYER_TODAY, "PAID", 10_000L, todayMidnight),
                new SeedOrder(ORDER_TODAY_NOON, BUYER_TODAY, "PAID", 30_000L, todayNoon),
                new SeedOrder(ORDER_YESTERDAY_LAST_SECOND, BUYER_YESTERDAY, "PAID", 5_000L, yesterdayLastSecond),
                new SeedOrder(ORDER_PREV_MONTH, BUYER_PREV_MONTH, "CONFIRMED", 20_000L, previousMonthMid),
                new SeedOrder(ORDER_PENDING_PAYMENT, BUYER_TODAY, "PENDING_PAYMENT", 50_000L, null),
                new SeedOrder(ORDER_31_DAYS_AGO, BUYER_PREV_MONTH, "CONFIRMED", 7_000L, daysAgo31),
                new SeedOrder(ORDER_29_DAYS_AGO, BUYER_PREV_MONTH, "CONFIRMED", 8_000L, daysAgo29));
        items = List.of(
                new SeedItem(ID_BASE + 1, ORDER_TODAY_MIDNIGHT, PRODUCT_A, SELLER_A, 1, 10_000L, "PAID", null),
                new SeedItem(ID_BASE + 2, ORDER_TODAY_NOON, PRODUCT_A, SELLER_A, 2, 20_000L, "PAID", null),
                new SeedItem(ID_BASE + 3, ORDER_TODAY_NOON, PRODUCT_B, SELLER_B, 1, 10_000L, "PAID", null),
                new SeedItem(ITEM_YESTERDAY, ORDER_YESTERDAY_LAST_SECOND, PRODUCT_A, SELLER_A, 1, 5_000L,
                        "CANCEL_REQUESTED", null),
                new SeedItem(ITEM_PREV_MONTH, ORDER_PREV_MONTH, PRODUCT_A, SELLER_A, 2, 20_000L, "CONFIRMED",
                        previousMonthMid),
                new SeedItem(ID_BASE + 6, ORDER_PENDING_PAYMENT, PRODUCT_B, SELLER_B, 5, 50_000L, "ORDERED", null),
                new SeedItem(ID_BASE + 7, ORDER_31_DAYS_AGO, PRODUCT_B, SELLER_B, 1, 7_000L, "CONFIRMED", daysAgo31),
                new SeedItem(ID_BASE + 8, ORDER_29_DAYS_AGO, PRODUCT_B, SELLER_B, 1, 8_000L, "CONFIRMED", daysAgo29));
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인가: 비인증 401 · BUYER 403 · ADMIN 200")
    void authorization() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_TODAY))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 요약: 매출=paid_at 합·환불 COMPLETED만 차감·신규회원 BUYER role만 · 경계=오늘 00:00 포함·어제 23:59:59 제외 · 미결제 제외")
    void summary() throws Exception {
        JsonNode before = fetch();
        seed();
        JsonNode after = fetch();

        LocalDateTime tomorrow = today.plusDays(1).atStartOfDay();
        LocalDateTime yesterday = today.minusDays(1).atStartOfDay();
        LocalDateTime thisMonthStart = thisMonth.atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = thisMonth.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime previousMonthStart = previousMonth.atDay(1).atStartOfDay();

        assertPeriod(before, after, "today", todayMidnight, tomorrow, REFUND_TODAY_AMOUNT, 1);
        assertPeriod(before, after, "previousDay", yesterday, todayMidnight, 0, 1);
        boolean yesterdayInThisMonth = YearMonth.from(today.minusDays(1)).equals(thisMonth);
        assertPeriod(before, after, "thisMonth", thisMonthStart, nextMonthStart, REFUND_TODAY_AMOUNT,
                yesterdayInThisMonth ? 2 : 1);
        assertPeriod(before, after, "previousMonth", previousMonthStart, thisMonthStart, REFUND_PREV_MONTH_AMOUNT,
                yesterdayInThisMonth ? 1 : 2);

        // 경계 확인: 오늘 정각 주문은 오늘에만, 어제 23:59:59 주문은 어제에만 (기대값 자체가 반구간으로 계산됨)
        assertThat(revenue(todayMidnight, tomorrow)).isEqualTo(40_000L);
        assertThat(revenue(yesterday, todayMidnight)).isEqualTo(5_000L);
    }

    private void assertPeriod(JsonNode before, JsonNode after, String period, LocalDateTime from, LocalDateTime to,
            long expectedRefund, long expectedNewMembers) {
        long expectedRevenue = revenue(from, to);
        assertThat(delta(before, after, "summary", period, "revenue")).as(period + ".revenue").isEqualTo(expectedRevenue);
        assertThat(delta(before, after, "summary", period, "refund")).as(period + ".refund").isEqualTo(expectedRefund);
        assertThat(delta(before, after, "summary", period, "netRevenue")).as(period + ".netRevenue")
                .isEqualTo(expectedRevenue - expectedRefund);
        assertThat(delta(before, after, "summary", period, "orderCount")).as(period + ".orderCount")
                .isEqualTo(paidOrders(from, to).count());
        assertThat(delta(before, after, "summary", period, "newMemberCount")).as(period + ".newMemberCount")
                .isEqualTo(expectedNewMembers);
    }

    @Test
    @DisplayName("T3 처리 대기: 정산 PENDING 1·클레임 REQUESTED 1·배송 대기(PAID 품목) 3·재고 임박(1~5·수동 품절 제외) 1"
            + "·상품 승인 대기 1(삭제 제외)·셀러 승인 대기 1(삭제 제외)·클레임 처리 대기 0(RETURN APPROVED 회수 송장 대기는 비대상)")
    void pending() throws Exception {
        JsonNode before = fetch();
        seed();
        JsonNode after = fetch();

        assertThat(delta(before, after, "pending", "settlementPending")).isEqualTo(1);
        assertThat(delta(before, after, "pending", "claimRequested")).isEqualTo(1);
        long paidItems = items.stream().filter(item -> "PAID".equals(item.itemStatus())).count();
        assertThat(paidItems).isEqualTo(3);
        assertThat(delta(before, after, "pending", "deliveryReady")).isEqualTo(paidItems);
        // 가용 3(포함)·0·6·variant 수동품절 2·product 수동품절 2 → 1건
        assertThat(delta(before, after, "pending", "lowStock")).isEqualTo(1);
        // Track 96-2 D-203: PENDING 2건 중 deleted_at 있는 1건 제외(@SQLRestriction) · SALE·ACTIVE는 미집계
        assertThat(delta(before, after, "pending", "productPending")).isEqualTo(1);
        assertThat(delta(before, after, "pending", "sellerPending")).isEqualTo(1);
        // Track 96-4 D-205: RETURN APPROVED는 회수 송장 없음(구매자 대기)이라 후속 액션 0 · CANCEL REQUESTED는 FOLLOWUP 비대상(매트릭스는 AdminClaimActionFilterIntegrationTest)
        assertThat(delta(before, after, "pending", "claimFollowup")).isZero();
    }

    @Test
    @DisplayName("T4 추이: monthlyRevenue 6개·dailyOrders 30개 고정, 빈 구간 0, 30일 경계(29일 전 포함·31일 전 제외)")
    void trends() throws Exception {
        JsonNode before = fetch();
        seed();
        JsonNode after = fetch();

        JsonNode monthly = after.get("monthlyRevenue");
        assertThat(monthly).hasSize(TREND_MONTHS);
        for (int offset = 0; offset < TREND_MONTHS; offset++) {
            YearMonth month = thisMonth.minusMonths(TREND_MONTHS - 1 - offset);
            String key = month.format(MONTH_KEY);
            assertThat(monthly.get(offset).get("yearMonth").asText()).isEqualTo(key);
            LocalDateTime from = month.atDay(1).atStartOfDay();
            LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
            long expectedRefund = month.equals(thisMonth) ? REFUND_TODAY_AMOUNT
                    : month.equals(previousMonth) ? REFUND_PREV_MONTH_AMOUNT : 0;
            assertThat(bucketDelta(before, after, "monthlyRevenue", "yearMonth", key, "revenue")).as(key)
                    .isEqualTo(revenue(from, to));
            assertThat(bucketDelta(before, after, "monthlyRevenue", "yearMonth", key, "orderCount")).as(key)
                    .isEqualTo(paidOrders(from, to).count());
            assertThat(bucketDelta(before, after, "monthlyRevenue", "yearMonth", key, "refund")).as(key)
                    .isEqualTo(expectedRefund);
            assertThat(bucketDelta(before, after, "monthlyRevenue", "yearMonth", key, "netRevenue")).as(key)
                    .isEqualTo(revenue(from, to) - expectedRefund);
        }
        // 4개월 전은 시드 없음 → 차분 0이면서 행 자체는 존재(빈 달 0 채움)
        assertThat(find(monthly, "yearMonth", thisMonth.minusMonths(4).format(MONTH_KEY))).isNotNull();

        JsonNode daily = after.get("dailyOrders");
        assertThat(daily).hasSize(TREND_DAYS);
        for (int offset = 0; offset < TREND_DAYS; offset++) {
            LocalDate day = today.minusDays(TREND_DAYS - 1 - offset);
            String key = day.format(DAY_KEY);
            assertThat(daily.get(offset).get("date").asText()).isEqualTo(key);
            LocalDateTime from = day.atStartOfDay();
            LocalDateTime to = day.plusDays(1).atStartOfDay();
            assertThat(bucketDelta(before, after, "dailyOrders", "date", key, "orderCount")).as(key)
                    .isEqualTo(paidOrders(from, to).count());
            assertThat(bucketDelta(before, after, "dailyOrders", "date", key, "revenue")).as(key)
                    .isEqualTo(revenue(from, to));
        }
        // 29일 전(첫 칸·정각)은 포함·31일 전은 배열 밖
        assertThat(bucketDelta(before, after, "dailyOrders", "date", today.minusDays(29).format(DAY_KEY), "revenue"))
                .isEqualTo(8_000L);
        assertThat(find(daily, "date", today.minusDays(31).format(DAY_KEY))).isNull();
    }

    @Test
    @DisplayName("T5 최근 주문·클레임 5: 결제 시각 내림차순(미결제 제외)·buyerName 원문·클레임 요청 시각 내림차순 + orderNo")
    void recentLists() throws Exception {
        seed();
        JsonNode after = fetch();

        JsonNode recentOrders = after.get("recentOrders");
        assertThat(recentOrders.size()).isBetween(2, 5);
        assertThat(recentOrders.get(0).get("orderNo").asText()).isEqualTo(orderNo(ORDER_TODAY_NOON));
        assertThat(recentOrders.get(0).get("orderPublicId").asText()).isEqualTo(publicId("ord", ORDER_TODAY_NOON));
        assertThat(recentOrders.get(0).get("buyerName").asText()).isEqualTo("오늘구매자");
        assertThat(recentOrders.get(0).get("totalPrice").asLong()).isEqualTo(30_000L);
        assertThat(recentOrders.get(0).get("status").asText()).isEqualTo("PAID");
        assertThat(recentOrders.get(0).get("paidAt").asText()).endsWith("+09:00");
        assertThat(recentOrders.get(1).get("orderNo").asText()).isEqualTo(orderNo(ORDER_TODAY_MIDNIGHT));
        for (JsonNode row : recentOrders) {
            assertThat(row.get("orderNo").asText()).isNotEqualTo(orderNo(ORDER_PENDING_PAYMENT));
        }

        JsonNode recentClaims = after.get("recentClaims");
        assertThat(recentClaims.size()).isBetween(2, 5);
        assertThat(recentClaims.get(0).get("claimPublicId").asText()).isEqualTo(publicId("clm", CLAIM_REQUESTED));
        assertThat(recentClaims.get(0).get("type").asText()).isEqualTo("CANCEL");
        assertThat(recentClaims.get(0).get("status").asText()).isEqualTo("REQUESTED");
        assertThat(recentClaims.get(0).get("orderNo").asText()).isEqualTo(orderNo(ORDER_YESTERDAY_LAST_SECOND));
        assertThat(recentClaims.get(0).get("requestedAt").asText()).endsWith("+09:00");
        assertThat(recentClaims.get(1).get("claimPublicId").asText()).isEqualTo(publicId("clm", CLAIM_APPROVED));
    }

    @Test
    @DisplayName("T6 상위 셀러·상품 5: 이번 달 결제 품목 total_price 합 내림차순·건수/수량 정확")
    void topLists() throws Exception {
        seed();
        JsonNode after = fetch();

        LocalDateTime from = thisMonth.atDay(1).atStartOfDay();
        LocalDateTime to = thisMonth.plusMonths(1).atDay(1).atStartOfDay();
        long sellerARevenue = itemSum(from, to, item -> item.sellerId() == SELLER_A, SeedItem::total);
        long sellerBRevenue = itemSum(from, to, item -> item.sellerId() == SELLER_B, SeedItem::total);
        // 셀러A는 오늘 30000이 확정이라 항상 B(최대 10000+8000+7000)보다 크다
        assertThat(sellerARevenue).isGreaterThan(sellerBRevenue);

        JsonNode topSellers = after.get("topSellers");
        assertThat(topSellers.size()).isLessThanOrEqualTo(5);
        JsonNode sellerA = find(topSellers, "sellerPublicId", publicId("slr", SELLER_A));
        JsonNode sellerB = find(topSellers, "sellerPublicId", publicId("slr", SELLER_B));
        assertThat(sellerA).isNotNull();
        assertThat(sellerB).isNotNull();
        assertThat(sellerA.get("sellerName").asText()).isEqualTo("대시보드셀러A");
        assertThat(sellerA.get("revenue").asLong()).isEqualTo(sellerARevenue);
        assertThat(sellerA.get("orderItemCount").asLong())
                .isEqualTo(itemSum(from, to, item -> item.sellerId() == SELLER_A, item -> 1L));
        assertThat(sellerB.get("revenue").asLong()).isEqualTo(sellerBRevenue);
        assertThat(indexOf(topSellers, sellerA)).isLessThan(indexOf(topSellers, sellerB));

        JsonNode topProducts = after.get("topProducts");
        assertThat(topProducts.size()).isLessThanOrEqualTo(5);
        JsonNode productA = find(topProducts, "productPublicId", publicId("prd", PRODUCT_A));
        JsonNode productB = find(topProducts, "productPublicId", publicId("prd", PRODUCT_B));
        assertThat(productA).isNotNull();
        assertThat(productB).isNotNull();
        assertThat(productA.get("productName").asText()).isEqualTo("대시보드상품A");
        assertThat(productA.get("revenue").asLong())
                .isEqualTo(itemSum(from, to, item -> item.productId() == PRODUCT_A, SeedItem::total));
        assertThat(productA.get("quantity").asLong())
                .isEqualTo(itemSum(from, to, item -> item.productId() == PRODUCT_A, item -> (long) item.quantity()));
        assertThat(productB.get("quantity").asLong())
                .isEqualTo(itemSum(from, to, item -> item.productId() == PRODUCT_B, item -> (long) item.quantity()));
        assertThat(indexOf(topProducts, productA)).isLessThan(indexOf(topProducts, productB));
    }

    /**
     * 정산 대사(D-180). 정산 gross는 "구매확정 품목·confirmed_at 월 귀속"({@code OrderItemRepository.aggregateGrossBySeller})이고
     * 대시보드 매출은 "결제완료 주문·paid_at 월 귀속"이라 기준이 다르다. 시드는 확정 품목의 confirmed_at을 결제 시각과 같게 두므로
     * 두 값의 차는 정확히 "그 달에 결제됐지만 미확정(PAID·CANCEL_REQUESTED 등)인 품목 합"이다. 미확정 품목이 없는 달은 일치한다.
     */
    @Test
    @DisplayName("T7 정산 대사: 대시보드 월 매출 == 정산 gross(CONFIRMED·confirmed_at) + 그 달 결제 미확정 품목 합 (전월·이번 달)")
    void settlementReconciliation() throws Exception {
        JsonNode before = fetch();
        seed();
        JsonNode after = fetch();

        assertReconciled(before, after, "previousMonth", previousMonth);
        assertReconciled(before, after, "thisMonth", thisMonth);
        // 이번 달은 오늘 결제 품목(PAID)이 미확정이라 정산 gross가 반드시 작다
        LocalDateTime from = thisMonth.atDay(1).atStartOfDay();
        LocalDateTime to = thisMonth.plusMonths(1).atDay(1).atStartOfDay();
        assertThat(delta(before, after, "summary", "thisMonth", "revenue"))
                .isGreaterThan(settlementGrossOfSeededSellers(thisMonth));
        assertThat(itemSum(from, to, item -> !"CONFIRMED".equals(item.itemStatus()), SeedItem::total)).isPositive();
    }

    private void assertReconciled(JsonNode before, JsonNode after, String period, YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        long settlementGross = settlementGrossOfSeededSellers(month);
        long unconfirmed = itemSum(from, to, item -> !"CONFIRMED".equals(item.itemStatus()), SeedItem::total);
        assertThat(settlementGross).as(period + " settlement gross")
                .isEqualTo(itemSum(from, to, item -> "CONFIRMED".equals(item.itemStatus()), SeedItem::total));
        assertThat(delta(before, after, "summary", period, "revenue")).as(period + " dashboard == settlement + unconfirmed")
                .isEqualTo(settlementGross + unconfirmed);
    }

    @Test
    @DisplayName("T8 V31: order.paid_at 인덱스(ix_order_paid_at) 존재")
    void paidAtIndexExists() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND INDEX_NAME = 'ix_order_paid_at' "
                + "AND COLUMN_NAME = 'paid_at'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 기대값(시드 목록 산술·반구간 [from, to)) ----------

    private Stream<SeedOrder> paidOrders(LocalDateTime from, LocalDateTime to) {
        return orders.stream().filter(order -> order.paidAt() != null)
                .filter(order -> !order.paidAt().isBefore(from) && order.paidAt().isBefore(to));
    }

    private long revenue(LocalDateTime from, LocalDateTime to) {
        return paidOrders(from, to).mapToLong(SeedOrder::total).sum();
    }

    private long itemSum(LocalDateTime from, LocalDateTime to, Predicate<SeedItem> filter,
            ToLongFunction<SeedItem> value) {
        Set<Long> paidOrderIds = paidOrders(from, to).map(SeedOrder::id).collect(Collectors.toSet());
        return items.stream().filter(item -> paidOrderIds.contains(item.orderId())).filter(filter).mapToLong(value).sum();
    }

    /** 정산 gross 소스와 같은 쿼리(CONFIRMED·confirmed_at 양끝 포함)로 시드 셀러(A·B)의 월 gross 합을 구한다. */
    private long settlementGrossOfSeededSellers(YearMonth month) {
        LocalDateTime periodStart = month.atDay(1).atStartOfDay();
        LocalDateTime periodEnd = month.atEndOfMonth().atTime(23, 59, 59, 999_999_000);
        List<SellerGrossProjection> rows = orderItemRepository.aggregateGrossBySeller(OrderItemStatus.CONFIRMED,
                periodStart, periodEnd);
        Set<Long> seededSellers = Set.of(SELLER_A, SELLER_B);
        return rows.stream().filter(row -> seededSellers.contains(row.getSellerId()))
                .mapToLong(SellerGrossProjection::getGrossAmount).sum();
    }

    // ---------- JSON helpers ----------

    private JsonNode fetch() throws Exception {
        String body = mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static long delta(JsonNode before, JsonNode after, String... path) {
        return at(after, path) - at(before, path);
    }

    private static long at(JsonNode node, String... path) {
        JsonNode current = node;
        for (String key : path) {
            current = current.get(key);
        }
        return current.asLong();
    }

    private static long bucketDelta(JsonNode before, JsonNode after, String array, String keyField, String key,
            String valueField) {
        return find(after.get(array), keyField, key).get(valueField).asLong()
                - find(before.get(array), keyField, key).get(valueField).asLong();
    }

    private static JsonNode find(JsonNode array, String keyField, String key) {
        for (JsonNode row : array) {
            if (key.equals(row.get(keyField).asText())) {
                return row;
            }
        }
        return null;
    }

    private static int indexOf(JsonNode array, JsonNode row) {
        for (int index = 0; index < array.size(); index++) {
            if (array.get(index).equals(row)) {
                return index;
            }
        }
        return -1;
    }

    private static String publicId(String prefix, long id) {
        return String.format("%s_DASH86%020d", prefix, id);
    }

    private static String orderNo(long id) {
        return "DASH86-" + id;
    }

    // ---------- seed·cleanup(바인딩 파라미터·정적 SQL·SQL injection 위험 없음) ----------

    private void seed() {
        Long buyerRoleId = jdbc.queryForObject("SELECT id FROM role WHERE code = 'BUYER'", Long.class);
        Long adminRoleId = jdbc.queryForObject("SELECT id FROM role WHERE code = 'ADMIN_OPERATOR'", Long.class);

        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                insertUser(BUYER_TODAY, "오늘구매자", todayNoon, buyerRoleId);
                insertUser(BUYER_YESTERDAY, "어제구매자", yesterdayLastSecond, buyerRoleId);
                insertUser(BUYER_PREV_MONTH, "전월구매자", previousMonthMid, buyerRoleId);
                // BUYER role 없는 오늘 가입자(운영 관리자) → 신규회원 미집계
                insertUser(NON_BUYER_TODAY, "오늘운영자", todayNoon, adminRoleId);

                insertSeller(SELLER_A, "대시보드셀러A");
                insertSeller(SELLER_B, "대시보드셀러B");
                // 승인 대기 셀러 1 + 승인 대기였다가 삭제된 셀러 1(집계 제외)
                insertSellerWithStatus(SELLER_PENDING, "대시보드승인대기셀러", "PENDING", false);
                insertSellerWithStatus(SELLER_PENDING_DELETED, "대시보드삭제셀러", "PENDING", true);
                insertProduct(PRODUCT_A, SELLER_A, "대시보드상품A", false);
                insertProduct(PRODUCT_B, SELLER_B, "대시보드상품B", false);
                insertProduct(PRODUCT_SOLDOUT, SELLER_B, "대시보드품절상품", true);
                // 승인 대기 상품 1 + 승인 대기였다가 삭제된 상품 1(집계 제외)
                insertProductWithStatus(PRODUCT_PENDING, SELLER_A, "대시보드승인대기상품", "PENDING", false);
                insertProductWithStatus(PRODUCT_PENDING_DELETED, SELLER_A, "대시보드삭제상품", "PENDING", true);
                // 재고: 가용 3(임박) · 0(품절) · 6(여유) · variant 수동품절 2 · product 수동품절 2
                insertVariantWithInventory(ID_BASE + 1, PRODUCT_A, false, 3);
                insertVariantWithInventory(ID_BASE + 2, PRODUCT_A, false, 0);
                insertVariantWithInventory(ID_BASE + 3, PRODUCT_B, false, 6);
                insertVariantWithInventory(ID_BASE + 4, PRODUCT_B, true, 2);
                insertVariantWithInventory(ID_BASE + 5, PRODUCT_SOLDOUT, false, 2);

                orders.forEach(this::insertOrder);
                items.forEach(this::insertItem);

                // 클레임: 어제 주문 품목 CANCEL REQUESTED(최신) · 전월 품목 RETURN APPROVED(구)
                insertClaim(CLAIM_REQUESTED, ITEM_YESTERDAY, "CANCEL", "REQUESTED", todayNoon);
                insertClaim(CLAIM_APPROVED, ITEM_PREV_MONTH, "RETURN", "APPROVED", previousMonthMid.plusDays(5));
                // 환불: 오늘 COMPLETED 3000(차감) · PENDING 999(미차감) · FAILED 777(미차감) · 전월 COMPLETED 4000
                insertRefund(ID_BASE + 1, CLAIM_REQUESTED, REFUND_TODAY_AMOUNT, "COMPLETED", todayNoon);
                insertRefund(ID_BASE + 2, CLAIM_REQUESTED, 999L, "PENDING", null);
                insertRefund(ID_BASE + 3, CLAIM_REQUESTED, 777L, "FAILED", null);
                insertRefund(ID_BASE + 4, CLAIM_APPROVED, REFUND_PREV_MONTH_AMOUNT, "COMPLETED", previousMonthMid.plusDays(6));

                insertSettlement(ID_BASE + 1, SELLER_A, "PENDING");
                insertSettlement(ID_BASE + 2, SELLER_B, "CONFIRMED");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertUser(long id, String name, LocalDateTime createdAt, long roleId) {
        jdbc.update("INSERT INTO user (id, public_id, email, name, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, publicId("usr", id), "dash86-" + id + "@example.com", name, createdAt, createdAt);
        jdbc.update("INSERT INTO user_role (id, user_id, role_id, created_at) VALUES (?, ?, ?, ?)", id, id, roleId, createdAt);
    }

    private void insertSeller(long id, String companyName) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, "
                + "updated_at) VALUES (?, ?, ?, '대표', 'ACTIVE', 1000, NOW(6), NOW(6))", id, publicId("slr", id), companyName);
    }

    private void insertSellerWithStatus(long id, String companyName, String status, boolean deleted) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, deleted_at, created_at, "
                + "updated_at) VALUES (?, ?, ?, '대표', ?, 1000, ?, NOW(6), NOW(6))",
                id, publicId("slr", id), companyName, status, deleted ? LocalDateTime.now() : null);
    }

    private void insertProductWithStatus(long id, long sellerId, String name, String status, boolean deleted) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "deleted_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 10000, 0, ?, NOW(6), NOW(6))",
                id, publicId("prd", id), sellerId, ID_BASE, name, status, deleted ? LocalDateTime.now() : null);
    }

    private void insertProduct(long id, long sellerId, String name, boolean soldoutManual) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, ?, NOW(6), NOW(6))",
                id, publicId("prd", id), sellerId, ID_BASE, name, soldoutManual);
    }

    private void insertVariantWithInventory(long id, long productId, boolean soldoutManual, int available) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 0, 'SALE', ?, 1, ?, NOW(6), NOW(6))",
                id, publicId("var", id), productId, "DASH86-" + id, soldoutManual, ID_BASE);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", id, id, available, available);
    }

    private void insertOrder(SeedOrder order) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                + "shipping_fee, paid_at, ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, NOW(6), NOW(6))",
                order.id(), publicId("ord", order.id()), order.buyerId(), orderNo(order.id()), order.status(), order.total(),
                order.paidAt(), order.paidAt());
    }

    private void insertItem(SeedItem item) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, "
                + "quantity, unit_price, total_price, commission_rate, item_status, confirmed_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1000, ?, ?, NOW(6), NOW(6))",
                item.id(), publicId("oit", item.id()), item.orderId(), item.productId(), ID_BASE + 1, item.sellerId(),
                item.productId() == PRODUCT_A ? "대시보드상품A" : "대시보드상품B", item.quantity(),
                item.total() / item.quantity(), item.total(), item.itemStatus(), item.confirmedAt());
    }

    private void insertClaim(long id, long orderItemId, String type, String status, LocalDateTime requestedAt) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, requested_at, "
                + "previous_order_item_status, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'CHANGE_MIND', ?, ?, ?, 'PAID', 0, NOW(6), NOW(6))",
                id, publicId("clm", id), orderItemId, type, status, BUYER_TODAY, requestedAt);
    }

    private void insertRefund(long id, long claimId, long amount, String status, LocalDateTime refundedAt) {
        jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, refunded_at, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, publicId("rfn", id), claimId, ID_BASE, amount, status, refundedAt);
    }

    private void insertSettlement(long id, long sellerId, String status) {
        LocalDateTime periodStart = previousMonth.atDay(1).atStartOfDay();
        LocalDateTime periodEnd = previousMonth.atEndOfMonth().atTime(23, 59, 59, 999_999_000);
        jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, "
                + "fee_amount, refund_amount, net_amount, commission_rate, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 20000, 2000, 0, 18000, 1000, ?, NOW(6), NOW(6))",
                id, sellerId, ID_BASE, periodStart, periodEnd, status);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM settlement WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM refund WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM inventory WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM user_role WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
                jdbc.update("DELETE FROM user WHERE id BETWEEN ? AND ?", ID_BASE, ID_END);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

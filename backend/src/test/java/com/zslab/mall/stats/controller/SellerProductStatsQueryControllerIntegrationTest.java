package com.zslab.mall.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
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
 * 셀러 상품 통계 통합 테스트(Track 90-E-3·D-200·실 MariaDB·HTTP 경유·90-E-1/2 IT 시드 방식). 집계가 셀러 범위라 절대값으로 단언한다.
 *
 * <p><b>시드 그래프</b>: 셀러 A(상태 파라미터)·B(ACTIVE)·C(데이터 0) / A 상품 PA·PA2·PA3·PA5(SALE)·PA4(STOPPED) / B 상품 PB(SALE) /
 * variant VA1(PA·가용 5)·VA2(PA·가용 0)·VA6(PA·HIDDEN·가용 0)·VA3(PA2·가용 0)·VA4(PA3·가용 10)·VA5(PA4·가용 0)·VA7(PA5·가용 3)·VB(PB·가용 0) /
 * 주문 M(03-10) — A1(PA·2개·20,000)+A2(PA2·5,000)+A6(PA5·5,000)+B1(PB·15,000) · N(03-20) — A3(PA·10,000) · P(02-15) — A4(PA3·3,000) · X(PENDING_PAYMENT) — A5(PA3).
 * 입고: VA1 +50(03-05)·+30(03-15)·ADJUST +5(03-06·제외) · VA3 +10(02-20·기간 밖) · VA4 +20(03-08) · VB +7(03-09).
 * 셀러 A 3월 = 판매 PA 30,000/3개·PA2 5,000/1개·PA5 5,000/1개 · 미판매 PA3 · 재고 회전 PA2(0일)·PA(ceil(5×31/3)=52)·PA5(93)·PA3(판매 없음) · 품절 옵션 2/5.
 */
@AutoConfigureMockMvc
class SellerProductStatsQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/seller/stats/products";
    private static final String MARCH = "?from=2026-03-01&to=2026-03-31";

    private static final long ID_BASE = 9920L;
    private static final long USER_A = ID_BASE;
    private static final long USER_B = ID_BASE + 1;
    private static final long USER_C = ID_BASE + 2;
    private static final long BUYER_ID = ID_BASE + 3;
    private static final long SELLER_A = ID_BASE;
    private static final long SELLER_B = ID_BASE + 1;
    private static final long SELLER_C = ID_BASE + 2;
    private static final long PRODUCT_A = ID_BASE;
    private static final long PRODUCT_A2 = ID_BASE + 1;
    private static final long PRODUCT_A3 = ID_BASE + 2;
    private static final long PRODUCT_A4 = ID_BASE + 3;
    private static final long PRODUCT_B = ID_BASE + 4;
    private static final long PRODUCT_A5 = ID_BASE + 5;
    private static final long PRODUCT_LAST = PRODUCT_A5;
    private static final long VARIANT_A1 = ID_BASE;
    private static final long VARIANT_A2 = ID_BASE + 1;
    private static final long VARIANT_A3 = ID_BASE + 2;
    private static final long VARIANT_A4 = ID_BASE + 3;
    private static final long VARIANT_A5 = ID_BASE + 4;
    private static final long VARIANT_A6 = ID_BASE + 5;
    private static final long VARIANT_B = ID_BASE + 6;
    private static final long VARIANT_A7 = ID_BASE + 7;
    private static final long VARIANT_LAST = VARIANT_A7;
    private static final long ORDER_M = ID_BASE;
    private static final long ORDER_N = ID_BASE + 1;
    private static final long ORDER_P = ID_BASE + 2;
    private static final long ORDER_X = ID_BASE + 3;
    private static final long ITEM_A1 = ID_BASE;
    private static final long ITEM_A2 = ID_BASE + 1;
    private static final long ITEM_B1 = ID_BASE + 2;
    private static final long ITEM_A3 = ID_BASE + 3;
    private static final long ITEM_A4 = ID_BASE + 4;
    private static final long ITEM_A5 = ID_BASE + 5;
    private static final long ITEM_A6 = ID_BASE + 6;
    private static final long HISTORY_BASE = ID_BASE;
    private static final int HISTORY_COUNT = 6;
    private static final long DUMMY_FK_ID = ID_BASE;

    private static final long PRODUCT_A_MARCH_REVENUE = 30_000L;
    private static final long PRODUCT_A2_MARCH_REVENUE = 5_000L;
    private static final long PRODUCT_A5_MARCH_REVENUE = 5_000L;
    private static final int MARCH_DAYS = 31;

    private static final String PRODUCT_A_PID = pid("prd_", "SPSPA");
    private static final String PRODUCT_A2_PID = pid("prd_", "SPSPA2");
    private static final String PRODUCT_A3_PID = pid("prd_", "SPSPA3");
    private static final String PRODUCT_A4_PID = pid("prd_", "SPSPA4");
    private static final String PRODUCT_A5_PID = pid("prd_", "SPSPA5");
    private static final String PRODUCT_B_PID = pid("prd_", "SPSPB");

    private static final Set<String> TOP_KEYS = Set.of("periodDays", "topProducts", "bottomProducts", "unsoldProducts", "stockTurnover",
            "soldOutOptionCount", "saleOptionCount");
    private static final Set<String> RANK_KEYS = Set.of("productKey", "productName", "revenue", "orderCount", "quantity");
    private static final Set<String> UNSOLD_KEYS = Set.of("productKey", "productName", "basePrice");
    private static final Set<String> TURNOVER_KEYS = Set.of("productKey", "productName", "inboundQuantity", "soldQuantity", "availableQuantity",
            "depletionDays");
    /** 셀러 노출 금지 키(구매자·타 셀러·주문 총액·원가). 관리자 대응 DTO가 없어 수동 목록만. */
    private static final Set<String> FORBIDDEN_KEYS = Set.of("buyer", "buyerId", "buyerName", "sellerId", "sellerName", "sellerPublicId",
            "discountAmount", "shippingFee", "totalPrice", "supplyPrice", "quantityOnHand", "quantityReserved");

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
    @DisplayName("T2 격리: A 상위 3(PA·PA2·PA5) · B 상위 1(PB)·미판매 0·품절 1/1 · C 전부 비어 있음 · 타 셀러 상품·재고 이력 0")
    void isolation() throws Exception {
        JsonNode sellerA = fetch(USER_A, MARCH);
        JsonNode sellerB = fetch(USER_B, MARCH);
        JsonNode sellerC = fetch(USER_C, MARCH);

        assertThat(sellerA.get("topProducts")).hasSize(3);
        assertThat(sellerA.toString()).doesNotContain(PRODUCT_B_PID).doesNotContain("통계상품B");
        assertThat(sellerB.get("topProducts")).hasSize(1);
        assertThat(sellerB.get("topProducts").get(0).get("productKey").asText()).isEqualTo(PRODUCT_B_PID);
        assertThat(sellerB.get("unsoldProducts")).isEmpty();
        assertThat(sellerB.get("stockTurnover")).hasSize(1);
        // B 입고 7은 B에만(혼합 주문 M의 A 품목 입고 이력과 섞이지 않음)
        assertThat(sellerB.get("stockTurnover").get(0).get("inboundQuantity").asLong()).isEqualTo(7);
        assertThat(sellerB.get("soldOutOptionCount").asLong()).isEqualTo(1);
        assertThat(sellerB.get("saleOptionCount").asLong()).isEqualTo(1);
        for (String key : List.of("topProducts", "bottomProducts", "unsoldProducts", "stockTurnover")) {
            assertThat(sellerC.get(key)).as(key).isEmpty();
        }
        assertThat(sellerC.get("soldOutOptionCount").asLong()).isZero();
        assertThat(sellerC.get("saleOptionCount").asLong()).isZero();
        assertThat(sellerC.get("periodDays").asLong()).isEqualTo(MARCH_DAYS);
    }

    @Test
    @DisplayName("T3 상위·하위: 상위 = 매출 DESC·동률 id ASC(PA 30,000 → PA2 → PA5) · 하위 = 역순(PA5 → PA2 → PA) · 판매 0(PA3)은 어느 쪽에도 없음 · 스냅샷 이름·public_id")
    void topAndBottom() throws Exception {
        JsonNode march = fetch(USER_A, MARCH);
        JsonNode top = march.get("topProducts");
        assertThat(top.get(0).get("productKey").asText()).isEqualTo(PRODUCT_A_PID);
        assertThat(top.get(0).get("productName").asText()).isEqualTo("통계상품A");
        assertThat(top.get(0).get("revenue").asLong()).isEqualTo(PRODUCT_A_MARCH_REVENUE);
        assertThat(top.get(0).get("orderCount").asLong()).isEqualTo(2);
        assertThat(top.get(0).get("quantity").asLong()).isEqualTo(3);
        assertThat(top.get(1).get("productKey").asText()).isEqualTo(PRODUCT_A2_PID);
        assertThat(top.get(1).get("revenue").asLong()).isEqualTo(PRODUCT_A2_MARCH_REVENUE);
        assertThat(top.get(2).get("productKey").asText()).isEqualTo(PRODUCT_A5_PID);
        assertThat(top.get(2).get("revenue").asLong()).isEqualTo(PRODUCT_A5_MARCH_REVENUE);

        JsonNode bottom = march.get("bottomProducts");
        assertThat(bottom).hasSize(3);
        assertThat(bottom.get(0).get("productKey").asText()).isEqualTo(PRODUCT_A5_PID);
        assertThat(bottom.get(1).get("productKey").asText()).isEqualTo(PRODUCT_A2_PID);
        assertThat(bottom.get(2).get("productKey").asText()).isEqualTo(PRODUCT_A_PID);
        assertThat(march.get("topProducts").toString()).doesNotContain(PRODUCT_A3_PID);
        assertThat(march.get("bottomProducts").toString()).doesNotContain(PRODUCT_A3_PID);
    }

    @Test
    @DisplayName("T4 미판매: 3월 = PA3만(SALE·판매 0·STOPPED PA4 제외·PENDING_PAYMENT 주문 미반영·현행 이름·basePrice) · 2월 = PA·PA2·PA5(PA3는 2월 판매)")
    void unsold() throws Exception {
        JsonNode march = fetch(USER_A, MARCH).get("unsoldProducts");
        assertThat(march).hasSize(1);
        assertThat(march.get(0).get("productKey").asText()).isEqualTo(PRODUCT_A3_PID);
        assertThat(march.get(0).get("productName").asText()).isEqualTo("통계상품A3");
        assertThat(march.get(0).get("basePrice").asLong()).isEqualTo(10_000L);
        assertThat(fetch(USER_A, MARCH).toString()).doesNotContain(PRODUCT_A4_PID);

        JsonNode february = fetch(USER_A, "?from=2026-02-01&to=2026-02-28").get("unsoldProducts");
        assertThat(february).hasSize(3);
        assertThat(february.get(0).get("productKey").asText()).isEqualTo(PRODUCT_A_PID);
        assertThat(february.get(1).get("productKey").asText()).isEqualTo(PRODUCT_A2_PID);
        assertThat(february.get(2).get("productKey").asText()).isEqualTo(PRODUCT_A5_PID);
    }

    @Test
    @DisplayName("T5 재고 회전(SALE 상품·소진 예상 ASC·판매 없음은 뒤): PA2 0일(가용 0) → PA 52일(80 입고·3 판매·가용 5) → PA5 93일 → PA3 판매 없음(depletionDays 생략·입고 20) · ADJUST·기간 밖 입고 제외")
    void stockTurnover() throws Exception {
        JsonNode rows = fetch(USER_A, MARCH).get("stockTurnover");
        assertThat(rows).hasSize(4);
        assertRow(rows.get(0), PRODUCT_A2_PID, 0, 1, 0, 0L);
        assertRow(rows.get(1), PRODUCT_A_PID, 80, 3, 5, 52L);
        assertRow(rows.get(2), PRODUCT_A5_PID, 0, 1, 3, 93L);
        assertRow(rows.get(3), PRODUCT_A3_PID, 20, 0, 10, null);
        assertThat(rows.toString()).doesNotContain(PRODUCT_A4_PID);
        // 2월: PA3 판매 1·가용 10·28일 → ceil(10×28/1) = 280 · PA 입고 0(3월 입고 제외)·판매 0 → 판매 없음
        JsonNode february = fetch(USER_A, "?from=2026-02-01&to=2026-02-28").get("stockTurnover");
        JsonNode pa3 = february.get(0);
        assertRow(pa3, PRODUCT_A3_PID, 0, 1, 10, 280L);
        assertThat(february.get(1).get("productKey").asText()).isEqualTo(PRODUCT_A_PID);
        assertThat(february.get(1).get("inboundQuantity").asLong()).isZero();
        assertThat(february.get(1).has("depletionDays")).isFalse();
        // PA2 입고 +10은 02-20이라 2월 입고
        JsonNode pa2 = february.get(2);
        assertThat(pa2.get("productKey").asText()).isEqualTo(PRODUCT_A2_PID);
        assertThat(pa2.get("inboundQuantity").asLong()).isEqualTo(10);
    }

    @Test
    @DisplayName("T6 현재 품절 옵션 수(기간 무관): A = 2(VA2·VA3) / 판매 중 옵션 5 · HIDDEN 옵션·STOPPED 상품 옵션 제외 · 기간을 바꿔도 동일")
    void soldOutOptions() throws Exception {
        JsonNode march = fetch(USER_A, MARCH);
        assertThat(march.get("soldOutOptionCount").asLong()).isEqualTo(2);
        assertThat(march.get("saleOptionCount").asLong()).isEqualTo(5);
        JsonNode february = fetch(USER_A, "?from=2026-02-01&to=2026-02-28");
        assertThat(february.get("soldOutOptionCount").asLong()).isEqualTo(2);
        assertThat(february.get("saleOptionCount").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("T7 기간 경계: 양끝 포함 · 365일 OK/366일 400 · from>to 400 · 형식 오류 400 · periodDays 에코")
    void periodBoundaries() throws Exception {
        assertThat(fetch(USER_A, "?from=2026-03-10&to=2026-03-10").get("topProducts")).hasSize(3);
        assertThat(fetch(USER_A, "?from=2026-03-11&to=2026-03-19").get("topProducts")).isEmpty();
        assertThat(fetch(USER_A, "?from=2026-03-20&to=2026-03-20").get("topProducts")).hasSize(1);
        mockMvc.perform(get(URL).headers(authHeaders.seller(USER_A)).param("from", "2025-04-01").param("to", "2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodDays").value(365));
        for (String query : List.of("?from=2025-03-31&to=2026-03-31", "?from=2026-03-11&to=2026-03-10", "?from=2026/03/01&to=2026-03-31",
                "?from=2026-03-01")) {
            mockMvc.perform(get(URL + query).headers(authHeaders.seller(USER_A)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    @Test
    @DisplayName("T8 응답 키 화이트리스트: 최상위 7·상위/하위 5·미판매 3·재고 회전 6(depletionDays 생략 허용) · 구매자·원가·주문 총액 키 0")
    void responseKeyWhitelist() throws Exception {
        JsonNode march = fetch(USER_A, MARCH);
        assertThat(keysOf(march)).containsExactlyInAnyOrderElementsOf(TOP_KEYS);
        for (JsonNode row : march.get("topProducts")) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(RANK_KEYS);
        }
        for (JsonNode row : march.get("unsoldProducts")) {
            assertThat(keysOf(row)).containsExactlyInAnyOrderElementsOf(UNSOLD_KEYS);
        }
        for (JsonNode row : march.get("stockTurnover")) {
            assertThat(TURNOVER_KEYS).containsAll(keysOf(row));
        }
        assertThat(allKeys(march)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        assertThat(march.toString()).doesNotContain("통계구매자");
    }

    @Test
    @DisplayName("T9 D-190: SUSPENDED 셀러 조회 200")
    void suspended_returns200() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);
        assertThat(fetch(USER_A, MARCH).get("topProducts")).hasSize(3);
    }

    @ParameterizedTest(name = "{0} 셀러 GET stats products → 401")
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

    private static void assertRow(JsonNode row, String productKey, long inbound, long sold, long available, Long depletionDays) {
        assertThat(row.get("productKey").asText()).isEqualTo(productKey);
        assertThat(row.get("inboundQuantity").asLong()).isEqualTo(inbound);
        assertThat(row.get("soldQuantity").asLong()).isEqualTo(sold);
        assertThat(row.get("availableQuantity").asLong()).isEqualTo(available);
        if (depletionDays == null) {
            assertThat(row.has("depletionDays")).isFalse();
        } else {
            assertThat(row.get("depletionDays").asLong()).isEqualTo(depletionDays);
        }
    }

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
                seedSellerWithOwner(USER_A, SELLER_A, "SPSUSA", "SPSSLA", "통계셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SPSUSB", "SPSSLB", "통계셀러B", SellerStatus.ACTIVE);
                seedSellerWithOwner(USER_C, SELLER_C, "SPSUSC", "SPSSLC", "통계셀러C", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO `user` (id, public_id, name, created_at, updated_at) VALUES (?, ?, '통계구매자', NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "SPSBUY"));
                seedProduct(PRODUCT_A, PRODUCT_A_PID, SELLER_A, "통계상품A", "SALE");
                seedProduct(PRODUCT_A2, PRODUCT_A2_PID, SELLER_A, "통계상품A2", "SALE");
                seedProduct(PRODUCT_A3, PRODUCT_A3_PID, SELLER_A, "통계상품A3", "SALE");
                seedProduct(PRODUCT_A4, PRODUCT_A4_PID, SELLER_A, "통계상품A4", "STOPPED");
                seedProduct(PRODUCT_B, PRODUCT_B_PID, SELLER_B, "통계상품B", "SALE");
                seedProduct(PRODUCT_A5, PRODUCT_A5_PID, SELLER_A, "통계상품A5", "SALE");
                seedVariantWithInventory(VARIANT_A1, PRODUCT_A, "SALE", 5);
                seedVariantWithInventory(VARIANT_A2, PRODUCT_A, "SALE", 0);
                seedVariantWithInventory(VARIANT_A3, PRODUCT_A2, "SALE", 0);
                seedVariantWithInventory(VARIANT_A4, PRODUCT_A3, "SALE", 10);
                seedVariantWithInventory(VARIANT_A5, PRODUCT_A4, "SALE", 0);
                seedVariantWithInventory(VARIANT_A6, PRODUCT_A, "HIDDEN", 0);
                seedVariantWithInventory(VARIANT_B, PRODUCT_B, "SALE", 0);
                seedVariantWithInventory(VARIANT_A7, PRODUCT_A5, "SALE", 3);

                seedOrder(ORDER_M, "SPSORM", "PAID", 47_000L, "2026-03-10 09:00:00");
                seedOrder(ORDER_N, "SPSORN", "PAID", 10_000L, "2026-03-20 09:00:00");
                seedOrder(ORDER_P, "SPSORP", "CONFIRMED", 3_000L, "2026-02-15 09:00:00");
                seedOrder(ORDER_X, "SPSORX", "PENDING_PAYMENT", 99_000L, null);
                seedOrderItem(ITEM_A1, "SPSA1", ORDER_M, SELLER_A, PRODUCT_A, VARIANT_A1, "통계상품A", 2, 20_000L);
                seedOrderItem(ITEM_A2, "SPSA2", ORDER_M, SELLER_A, PRODUCT_A2, VARIANT_A3, "통계상품A2", 1, 5_000L);
                seedOrderItem(ITEM_B1, "SPSB1", ORDER_M, SELLER_B, PRODUCT_B, VARIANT_B, "통계상품B", 1, 15_000L);
                seedOrderItem(ITEM_A3, "SPSA3", ORDER_N, SELLER_A, PRODUCT_A, VARIANT_A1, "통계상품A", 1, 10_000L);
                seedOrderItem(ITEM_A4, "SPSA4", ORDER_P, SELLER_A, PRODUCT_A3, VARIANT_A4, "통계상품A3", 1, 3_000L);
                seedOrderItem(ITEM_A5, "SPSA5", ORDER_X, SELLER_A, PRODUCT_A3, VARIANT_A4, "통계상품A3", 1, 99_000L);
                seedOrderItem(ITEM_A6, "SPSA6", ORDER_M, SELLER_A, PRODUCT_A5, VARIANT_A7, "통계상품A5", 1, 5_000L);

                seedHistory(HISTORY_BASE, VARIANT_A1, "INBOUND", 50, "2026-03-05 10:00:00");
                seedHistory(HISTORY_BASE + 1, VARIANT_A1, "INBOUND", 30, "2026-03-15 10:00:00");
                seedHistory(HISTORY_BASE + 2, VARIANT_A1, "ADJUST", 5, "2026-03-06 10:00:00");
                seedHistory(HISTORY_BASE + 3, VARIANT_A3, "INBOUND", 10, "2026-02-20 10:00:00");
                seedHistory(HISTORY_BASE + 4, VARIANT_A4, "INBOUND", 20, "2026-03-08 10:00:00");
                seedHistory(HISTORY_BASE + 5, VARIANT_B, "INBOUND", 7, "2026-03-09 10:00:00");
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

    private void seedProduct(long id, String publicId, long sellerId, String name, String status) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, is_soldout_manual, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 10000, 0, NOW(6), NOW(6))", id, publicId, sellerId, DUMMY_FK_ID, name, status);
    }

    private void seedVariantWithInventory(long id, long productId, String status, int available) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 0, ?, 0, 1, ?, NOW(6), NOW(6))", id, pid("var_", "SPSV" + (id - ID_BASE)), productId, "SPS-" + id, status, DUMMY_FK_ID);
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", id, id, available, available);
    }

    private void seedOrder(long id, String tag, String status, long totalPrice, String paidAt) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, NOW(6), NOW(6))",
                id, pid("ord_", tag), BUYER_ID, "ORD" + tag + id, status, totalPrice, "2026-03-01 00:00:00", paidAt);
    }

    private void seedOrderItem(long id, String tag, long orderId, long sellerId, long productId, long variantId, String productName, int quantity,
            long totalPrice) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, product_name, quantity, "
                + "unit_price, total_price, commission_rate, item_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1000, 'PAID', NOW(6), NOW(6))",
                id, pid("oit_", tag), orderId, productId, variantId, sellerId, productName, quantity, totalPrice / quantity, totalPrice);
    }

    /** inventory.id = variant id로 시드했으므로 inventory_id에 variant id를 그대로 쓴다. */
    private void seedHistory(long id, long inventoryId, String changeType, int delta, String createdAt) {
        jdbc.update("INSERT INTO inventory_history (id, inventory_id, change_type, quantity_delta, reference_type, created_at) "
                + "VALUES (?, ?, ?, ?, 'seller', ?)", id, inventoryId, changeType, delta, createdAt);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM inventory_history WHERE id BETWEEN ? AND ?", HISTORY_BASE, HISTORY_BASE + HISTORY_COUNT - 1);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ITEM_A1, ITEM_A6);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ORDER_M, ORDER_X);
                jdbc.update("DELETE FROM inventory WHERE id BETWEEN ? AND ?", VARIANT_A1, VARIANT_LAST);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", VARIANT_A1, VARIANT_LAST);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", PRODUCT_A, PRODUCT_LAST);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?, ?)", USER_A, USER_B, USER_C);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?, ?)", SELLER_A, SELLER_B, SELLER_C);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?, ?)", USER_A, USER_B, USER_C, BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

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

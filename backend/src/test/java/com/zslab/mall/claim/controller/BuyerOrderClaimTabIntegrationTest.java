package com.zslab.mall.claim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 주문내역 탭 통합(Track 101-B D-213) 통합 테스트(실 MariaDB·HTTP 경유).
 *
 * <p><b>커버</b>: T1 주문 목록 배지 집계(여러 품목·여러 유형·종결 제외) · T2 클레임 목록 기준 전환(관리자 대행 취소 포함) ·
 * T3 타인 주문 제외 · T4 유형 필터 · T5 최신순 정렬 · T6 요약의 주문번호·상품명 · T7 쿼리 수(N+1 없음) ·
 * T8 주문 1건·3건 목록 쿼리 수 동일(주문 수 비례 증가 차단) · T9 주문 목록 페이징.
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·id 9930~9949 고정.
 */
@AutoConfigureMockMvc
class BuyerOrderClaimTabIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_USER = 9930L;
    private static final long OTHER_BUYER = 9931L;
    private static final long ADMIN_USER = 9932L;
    private static final long SELLER_ID = 9930L;
    private static final long PRODUCT_ID = 9930L;
    private static final long VARIANT_ID = 9930L;
    private static final long ORDER_MINE = 9930L;
    private static final long ORDER_OTHER = 9931L;
    private static final long ITEM_A = 9930L;   // 활성 CANCEL 1 + 종결 RETURN 2(집계 제외)
    private static final long ITEM_B = 9931L;   // 활성 RETURN 1
    private static final long ITEM_C = 9932L;   // 활성 EXCHANGE 1
    private static final long ITEM_OTHER = 9933L;
    /** 주문 수 비교용 구매자(T8·T9) — 주문 3건(9932~9934) · 각 품목 1개(9934~9936) · 클레임 없음. */
    private static final long MULTI_BUYER = 9933L;
    private static final long MULTI_ORDER_FROM = 9932L;
    private static final long MULTI_ITEM_FROM = 9934L;
    private static final int MULTI_ORDER_COUNT = 3;
    private static final long CLAIM_FROM = 9930L;
    private static final long CLAIM_TO = 9939L;
    private static final long DUMMY_FK_ID = 9930L;
    private static final long ITEM_PRICE = 10_000L;

    /**
     * 주문 목록 1회 호출 쿼리 예산. 실측 9(2026-09-24·주문 페이지 select + count + items fetch join + 클레임 배치 + 인증 조회
     * + Track 105-2d items[] 배치 4 — 상품·variant·셀러·교환 완료 클레임)이며 여유 1을 더해 둔다 — 잡으려는 것은 품목·클레임이
     * 행마다 조회되는 N+1 회귀(그 경우 수십 건이 된다)다. items[] 배치는 페이지당 고정이라 주문 수와 무관하다.
     * Track 106-1 품목 리뷰 상태 배치(findWrittenByOrderItemIdIn 페이지당 1회)로 9 → 10이 되어 상한도 10 → 11로 올린다(여유 1 유지).
     * 퀄리티 B 3-1(2026-10-06): 주문마다 나가던 배송지 스냅샷 조회를 없애 꽉 찬 첫 페이지(count 포함) 실측이 주문 1건 11 → 10 ·
     * 3건 13 → 10이 되어 상한을 실측값 10으로 낮춘다. 주문 수 비례 증가는 T8의 1건·3건 동일 단언이 따로 잡는다.
     */
    private static final int ORDER_LIST_QUERY_BUDGET = 10;
    /**
     * 클레임 목록 1회 호출 쿼리 예산. 실측 4(2026-09-23·목록 select + count + 환불 배치 + 주문·품목 projection)이며 여유 1.
     * 외부 검토 반영으로 품목 엔티티 배치 조회가 주문 projection에 합쳐져 5 → 4가 됐다. Track 105-4b 썸네일 상품 배치
     * (findByIdIn 페이지당 1회)로 4 → 5가 되어 상한도 5 → 6으로 올린다(여유 1 유지).
     */
    private static final int CLAIM_LIST_QUERY_BUDGET = 6;

    private static final String ORDER_MINE_PID = pid("ord_", "BOCTORD1");
    private static final String ORDER_OTHER_PID = pid("ord_", "BOCTORD2");
    private static final String ORDER_NO_MINE = "ORDBOCT9930";
    private static final String ORDERS_URL = "/api/v1/orders";
    private static final String CLAIMS_URL = "/api/v1/claims";
    private static final String MINE_PATH = "$.items[?(@.orderId == '" + ORDER_MINE_PID + "')]";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

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
    @DisplayName("T1 주문 목록 배지: 활성 클레임만 유형별 건수(CANCEL 1·RETURN 1·EXCHANGE 1)·종결 2건 제외")
    void orderList_activeClaimCounts() throws Exception {
        mockMvc.perform(get(ORDERS_URL).with(authHeaders.buyer(BUYER_USER)).param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(MINE_PATH + ".activeClaims.length()").value(3))
                .andExpect(jsonPath(MINE_PATH + ".activeClaims[?(@.claimType == 'CANCEL')].count").value(1))
                .andExpect(jsonPath(MINE_PATH + ".activeClaims[?(@.claimType == 'RETURN')].count").value(1))
                .andExpect(jsonPath(MINE_PATH + ".activeClaims[?(@.claimType == 'EXCHANGE')].count").value(1));
    }

    @Test
    @DisplayName("T2 클레임 목록: 관리자 대행 생성(requested_by=관리자) 건도 구매자 목록에 보인다(기준 = 주문 구매자)")
    void claimList_includesAdminCreated() throws Exception {
        mockMvc.perform(get(CLAIMS_URL).with(authHeaders.buyer(BUYER_USER)).param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(5))
                .andExpect(jsonPath("$.items[?(@.publicId == '" + claimPid(9930) + "')].claimType").value("CANCEL"));
    }

    @Test
    @DisplayName("T3 클레임 목록: 타인 주문의 클레임은 제외된다")
    void claimList_excludesOtherBuyer() throws Exception {
        mockMvc.perform(get(CLAIMS_URL).with(authHeaders.buyer(OTHER_BUYER)).param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].publicId").value(claimPid(9935)));
    }

    @Test
    @DisplayName("T4 유형 필터: type=RETURN → 반품만(활성·종결 모두)")
    void claimList_typeFilter() throws Exception {
        mockMvc.perform(get(CLAIMS_URL).with(authHeaders.buyer(BUYER_USER)).param("type", "RETURN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[?(@.claimType != 'RETURN')]").doesNotExist());
    }

    @Test
    @DisplayName("T5 정렬: 요청 시각 내림차순(최신 먼저)")
    void claimList_sortedByRequestedAtDesc() throws Exception {
        mockMvc.perform(get(CLAIMS_URL).with(authHeaders.buyer(BUYER_USER)).param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].publicId").value(claimPid(9934)))
                .andExpect(jsonPath("$.items[4].publicId").value(claimPid(9930)));
    }

    @Test
    @DisplayName("T6 요약 응답: 주문번호·상품명이 채워진다(배치 조립)")
    void claimList_carriesOrderNoAndProductName() throws Exception {
        mockMvc.perform(get(CLAIMS_URL).with(authHeaders.buyer(BUYER_USER)).param("type", "EXCHANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderNo").value(ORDER_NO_MINE))
                .andExpect(jsonPath("$.items[0].productName").value("탭통합상품"));
    }

    @Test
    @DisplayName("T7 쿼리 수: 주문 목록·클레임 목록 모두 페이지 단위 배치(N+1 없음)")
    void lists_stayWithinQueryBudget() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        statistics.clear();
        mockMvc.perform(get(ORDERS_URL).with(authHeaders.buyer(BUYER_USER)).param("size", "20"))
                .andExpect(status().isOk());
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(ORDER_LIST_QUERY_BUDGET);

        statistics.clear();
        mockMvc.perform(get(CLAIMS_URL).with(authHeaders.buyer(BUYER_USER)).param("size", "20"))
                .andExpect(status().isOk());
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(CLAIM_LIST_QUERY_BUDGET);

        statistics.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("T8 쿼리 수: 주문 1건 페이지(BUYER_USER)와 3건 페이지(MULTI_BUYER)의 쿼리 수가 같고 ≤ 예산 · 페이징 필드(page·size·totalCount·hasNext)")
    void orderList_queryCountIndependentOfOrderCount() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            // 두 요청 모두 꽉 찬 첫 페이지라 count 쿼리가 똑같이 나간다(덜 찬 첫 페이지는 count를 생략해 비교가 어긋난다).
            statistics.clear();
            mockMvc.perform(get(ORDERS_URL).with(authHeaders.buyer(BUYER_USER)).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(1))
                    .andExpect(jsonPath("$.totalCount").value(1))
                    .andExpect(jsonPath("$.hasNext").value(false));
            long singleOrderQueries = statistics.getPrepareStatementCount();

            statistics.clear();
            mockMvc.perform(get(ORDERS_URL).with(authHeaders.buyer(MULTI_BUYER)).param("size", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(MULTI_ORDER_COUNT))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(MULTI_ORDER_COUNT))
                    .andExpect(jsonPath("$.totalCount").value(MULTI_ORDER_COUNT))
                    .andExpect(jsonPath("$.hasNext").value(false));
            long multiOrderQueries = statistics.getPrepareStatementCount();

            assertThat(multiOrderQueries).as("주문 1건 %d회 · 3건 %d회", singleOrderQueries, multiOrderQueries)
                    .isEqualTo(singleOrderQueries);
            assertThat(multiOrderQueries).isLessThanOrEqualTo(ORDER_LIST_QUERY_BUDGET);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    @DisplayName("T9 페이징: 주문 3건을 size=2로 나누면 0쪽 2건·다음 있음 / 1쪽 1건·다음 없음(totalCount 3)")
    void orderList_appliesPaging() throws Exception {
        mockMvc.perform(get(ORDERS_URL).with(authHeaders.buyer(MULTI_BUYER)).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalCount").value(MULTI_ORDER_COUNT))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get(ORDERS_URL).with(authHeaders.buyer(MULTI_BUYER)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalCount").value(MULTI_ORDER_COUNT))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * 내 주문 1건(품목 3개) + 타인 주문 1건(품목 1개). 내 주문 클레임 5건 — 9930 관리자 대행 CANCEL(활성·requested_by=관리자) ·
     * 9931 RETURN 종결(REJECTED) · 9932 RETURN 종결(COMPLETED) · 9933 RETURN 활성 · 9934 EXCHANGE 활성.
     * 타인 주문 클레임 1건(9935). requested_at을 id 순으로 벌려 정렬 검증이 가능하게 한다.
     * 주문 수 비교용 구매자(MULTI_BUYER) 주문 3건(품목 각 1개·클레임 없음).
     */
    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_USER, pid("usr_", "BOCTUSR1"));
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        OTHER_BUYER, pid("usr_", "BOCTUSR2"));
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        ADMIN_USER, pid("usr_", "BOCTUSR3"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '탭통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BOCTSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '탭통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "BOCTPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCBOCT', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "BOCTVAR"), PRODUCT_ID, DUMMY_FK_ID);
                insertOrder(ORDER_MINE, ORDER_MINE_PID, BUYER_USER, ORDER_NO_MINE, ITEM_PRICE * 3);
                insertOrder(ORDER_OTHER, ORDER_OTHER_PID, OTHER_BUYER, "ORDBOCT9931", ITEM_PRICE);
                insertItem(ITEM_A, "BOCTITM1", ORDER_MINE);
                insertItem(ITEM_B, "BOCTITM2", ORDER_MINE);
                insertItem(ITEM_C, "BOCTITM3", ORDER_MINE);
                insertItem(ITEM_OTHER, "BOCTITM4", ORDER_OTHER);
                // 관리자 대행 취소: requested_by가 관리자라 구 기준(requested_by)에서는 구매자 목록에 잡히지 않는다.
                insertClaim(9930, ITEM_A, "CANCEL", "REQUESTED", ADMIN_USER, "2026-09-01 10:00:00");
                insertClaim(9931, ITEM_A, "RETURN", "REJECTED", BUYER_USER, "2026-09-02 10:00:00");
                insertClaim(9932, ITEM_A, "RETURN", "COMPLETED", BUYER_USER, "2026-09-03 10:00:00");
                insertClaim(9933, ITEM_B, "RETURN", "APPROVED", BUYER_USER, "2026-09-04 10:00:00");
                insertClaim(9934, ITEM_C, "EXCHANGE", "REQUESTED", BUYER_USER, "2026-09-05 10:00:00");
                insertClaim(9935, ITEM_OTHER, "CANCEL", "REQUESTED", OTHER_BUYER, "2026-09-06 10:00:00");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        MULTI_BUYER, pid("usr_", "BOCTUSR4"));
                for (int index = 0; index < MULTI_ORDER_COUNT; index++) {
                    long orderId = MULTI_ORDER_FROM + index;
                    insertOrder(orderId, pid("ord_", "BOCTORDM" + index), MULTI_BUYER, "ORDBOCTM" + orderId, ITEM_PRICE);
                    insertItem(MULTI_ITEM_FROM + index, "BOCTITMM" + index, orderId);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertOrder(long id, String publicId, long buyerId, String orderNo, long totalPrice) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                id, publicId, buyerId, orderNo, totalPrice);
    }

    private void insertItem(long id, String tag, long orderId) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'PAID', NOW(6), NOW(6), '탭통합상품', 1000)",
                id, pid("oit_", tag), orderId, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE);
    }

    private void insertClaim(long id, long orderItemId, String type, String claimStatus, long requestedBy, String requestedAt) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                        + "requested_at, previous_order_item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'BUYER_CHANGED_MIND', ?, ?, ?, 'PAID', NOW(6), NOW(6))",
                id, claimPid((int) id), orderItemId, type, claimStatus, requestedBy, requestedAt);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", CLAIM_FROM, CLAIM_TO);
                jdbc.update("DELETE FROM order_item WHERE id IN (?, ?, ?, ?)", ITEM_A, ITEM_B, ITEM_C, ITEM_OTHER);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?",
                        MULTI_ITEM_FROM, MULTI_ITEM_FROM + MULTI_ORDER_COUNT - 1);
                jdbc.update("DELETE FROM `order` WHERE id IN (?, ?)", ORDER_MINE, ORDER_OTHER);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?",
                        MULTI_ORDER_FROM, MULTI_ORDER_FROM + MULTI_ORDER_COUNT - 1);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?, ?)", BUYER_USER, OTHER_BUYER, ADMIN_USER, MULTI_BUYER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String claimPid(int id) {
        return pid("clm_", "BOCTC" + id);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

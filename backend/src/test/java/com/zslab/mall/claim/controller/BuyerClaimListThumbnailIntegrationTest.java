package com.zslab.mall.claim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 클레임 목록 썸네일 {@code GET /api/v1/claims} thumbnailUrl(Track 105-4b·D-224) 통합 테스트(실 MariaDB·HTTP 경유).
 *
 * <p><b>커버</b>: T1 썸네일 있음 → 값 · T2 삭제 상품 → 키 생략 · T3 썸네일 미등록 → 키 생략 · T4 쿼리 수 고정(클레임 1건 페이지 =
 * 3건 페이지·상품이 모두 다름 — 행마다 상품을 조회하면 +2).
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·id 985300~985329 고정.
 */
@AutoConfigureMockMvc
class BuyerClaimListThumbnailIntegrationTest extends AbstractIntegrationTest {

    private static final String CLAIMS_URL = "/api/v1/claims";
    private static final String THUMBNAIL_URL = "https://cdn.example.com/products/thumb-985300.jpg";

    private static final long ID_BASE = 985300L;
    private static final long ID_LAST = 985329L;
    private static final long BUYER_USER = ID_BASE;
    private static final long SELLER_ID = ID_BASE;
    private static final long ORDER_ID = ID_BASE;
    private static final long PRODUCT_WITH_THUMBNAIL = ID_BASE;
    private static final long PRODUCT_DELETED = ID_BASE + 1;
    private static final long PRODUCT_NO_THUMBNAIL = ID_BASE + 2;
    private static final long DUMMY_FK_ID = ID_BASE;
    private static final long ITEM_PRICE = 10_000L;

    private static final String CLAIM_WITH_THUMBNAIL = claimPid(ID_BASE);
    private static final String CLAIM_DELETED_PRODUCT = claimPid(ID_BASE + 1);
    private static final String CLAIM_NO_THUMBNAIL = claimPid(ID_BASE + 2);

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
    @DisplayName("T1 썸네일 있음: 클레임 → 주문 품목 → 상품 thumbnail_url")
    void claimList_carriesThumbnail() throws Exception {
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(claimPath(CLAIM_WITH_THUMBNAIL) + ".thumbnailUrl").value(THUMBNAIL_URL));
    }

    @Test
    @DisplayName("T2 삭제 상품: thumbnail_url이 있어도 키 생략(@SQLRestriction·상품명 스냅샷은 유지)")
    void claimList_deletedProduct_omitsThumbnail() throws Exception {
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(claimPath(CLAIM_DELETED_PRODUCT), hasSize(1)))
                .andExpect(jsonPath(claimPath(CLAIM_DELETED_PRODUCT) + ".thumbnailUrl", hasSize(0)))
                .andExpect(jsonPath(claimPath(CLAIM_DELETED_PRODUCT) + ".productName").value("삭제상품"));
    }

    @Test
    @DisplayName("T3 썸네일 미등록: 키 생략(NON_NULL)")
    void claimList_noThumbnail_omitsThumbnail() throws Exception {
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(claimPath(CLAIM_NO_THUMBNAIL), hasSize(1)))
                .andExpect(jsonPath(claimPath(CLAIM_NO_THUMBNAIL) + ".thumbnailUrl", hasSize(0)));
    }

    @Test
    @DisplayName("T4 쿼리 수 고정: 클레임 1건 페이지와 3건 페이지(상품 3개)의 쿼리 수가 같다(N+1 없음)")
    void claimList_queryCountIndependentOfRows() throws Exception {
        assertThat(countListQueries(3)).isEqualTo(countListQueries(1));
    }

    /** size를 바꿔 목록 1회 호출의 prepared statement 수를 잰다. 두 크기 모두 첫 페이지가 가득 차 count 쿼리도 같이 나간다. */
    private long countListQueries(int size) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.buyer(BUYER_USER)).param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(size)));
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return count;
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * 주문 1건에 품목 3개(상품 3개: 썸네일 있음·삭제됨(썸네일 있음)·썸네일 NULL), 품목마다 클레임 1건. requested_at을 벌려
     * 최신순 첫 페이지 구성이 매번 같게 한다.
     */
    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_USER, pid("usr_", "BCLTUSR1"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '썸네일셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BCLTSLR"));
                insertProduct(PRODUCT_WITH_THUMBNAIL, "BCLTPRD1", "썸네일상품", THUMBNAIL_URL);
                insertProduct(PRODUCT_DELETED, "BCLTPRD2", "삭제상품", THUMBNAIL_URL);
                insertProduct(PRODUCT_NO_THUMBNAIL, "BCLTPRD3", "미등록상품", null);
                jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE id = ?", PRODUCT_DELETED);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "BCLTORD1"), BUYER_USER, "ORDBCLT" + ORDER_ID, ITEM_PRICE * 3);
                insertItem(ID_BASE, PRODUCT_WITH_THUMBNAIL, "썸네일상품");
                insertItem(ID_BASE + 1, PRODUCT_DELETED, "삭제상품");
                insertItem(ID_BASE + 2, PRODUCT_NO_THUMBNAIL, "미등록상품");
                insertClaim(ID_BASE, ID_BASE, "2026-09-01 10:00:00");
                insertClaim(ID_BASE + 1, ID_BASE + 1, "2026-09-02 10:00:00");
                insertClaim(ID_BASE + 2, ID_BASE + 2, "2026-09-03 10:00:00");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertProduct(long id, String tag, String name, String thumbnailUrl) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, thumbnail_url, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, ?, NOW(6), NOW(6))",
                id, pid("prd_", tag), SELLER_ID, DUMMY_FK_ID, name, thumbnailUrl);
    }

    private void insertItem(long id, long productId, String productName) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'PAID', NOW(6), NOW(6), ?, 1000)",
                id, pid("oit_", "BCLTIT" + id), ORDER_ID, productId, DUMMY_FK_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE,
                productName);
    }

    private void insertClaim(long id, long orderItemId, String requestedAt) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                        + "requested_at, previous_order_item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CANCEL', 'BUYER_CHANGED_MIND', 'REQUESTED', ?, ?, 'PAID', NOW(6), NOW(6))",
                id, claimPid(id), orderItemId, BUYER_USER, requestedAt);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String claimPath(String claimPublicId) {
        return "$.items[?(@.publicId == '" + claimPublicId + "')]";
    }

    private static String claimPid(long id) {
        return pid("clm_", "BCLTC" + id);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 클레임 상세 {@code GET /api/v1/claims/{id}}의 주문·대상 품목(Track 105-4g-3) 통합 테스트(실 MariaDB·HTTP 경유).
 *
 * <p><b>커버</b>: T1 orderId·orderNo·item(상품명·옵션·수량·썸네일) · T2 삭제 상품 → 썸네일 키 생략·상품명 스냅샷 유지 ·
 * T3 교환 → 옵션은 승인 스냅샷(originalOptionLabel) 우선 · T4 다른 구매자 404 불변 · T5 쿼리 수 상한(추가 고정 2쿼리).
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·id 985430~985459 고정.
 */
@AutoConfigureMockMvc
class BuyerClaimDetailOrderItemIntegrationTest extends AbstractIntegrationTest {

    /** 취소 클레임 상세 1회 쿼리 상한: 실측 8 = 기존 6 + 이번 추가 2(주문 projection·상품 썸네일 — 품목 1개 고정이라 여유 없음). */
    private static final long CANCEL_DETAIL_QUERY_BUDGET = 8L;

    private static final String CLAIMS_URL = "/api/v1/claims/";
    private static final String THUMBNAIL_URL = "https://cdn.example.com/products/thumb-985430.jpg";

    private static final long ID_BASE = 985430L;
    private static final long ID_LAST = 985459L;
    private static final long BUYER_USER = ID_BASE;
    private static final long OTHER_BUYER_USER = ID_BASE + 1;
    private static final long SELLER_ID = ID_BASE;
    private static final long ORDER_ID = ID_BASE;
    private static final long DUMMY_FK_ID = ID_BASE;
    private static final long PRODUCT_WITH_THUMBNAIL = ID_BASE;
    private static final long PRODUCT_DELETED = ID_BASE + 1;
    private static final long PRODUCT_EXCHANGE = ID_BASE + 2;

    private static final String ORDER_PID = pid("ord_", "BCDOORD");
    private static final String ORDER_NO = "20260920-BCDO01";
    private static final String CLAIM_CANCEL = claimPid(ID_BASE);
    private static final String CLAIM_DELETED_PRODUCT = claimPid(ID_BASE + 1);
    private static final String CLAIM_EXCHANGE = claimPid(ID_BASE + 2);

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
    @DisplayName("T1 주문·대상 품목: orderId(public_id) · orderNo · item 상품명·옵션·수량·썸네일")
    void detail_carriesOrderAndItem() throws Exception {
        mockMvc.perform(get(CLAIMS_URL + CLAIM_CANCEL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_PID))
                .andExpect(jsonPath("$.orderNo").value(ORDER_NO))
                .andExpect(jsonPath("$.item.productName").value("썸네일상품"))
                .andExpect(jsonPath("$.item.optionLabel").value("색상: 블랙"))
                .andExpect(jsonPath("$.item.quantity").value(2))
                .andExpect(jsonPath("$.item.thumbnailUrl").value(THUMBNAIL_URL));
    }

    @Test
    @DisplayName("T2 삭제 상품: 썸네일 키 생략(@SQLRestriction) · 상품명·수량은 주문 스냅샷 유지 · 옵션 없음 → 키 생략")
    void detail_deletedProduct_omitsThumbnail() throws Exception {
        mockMvc.perform(get(CLAIMS_URL + CLAIM_DELETED_PRODUCT).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.productName").value("삭제상품"))
                .andExpect(jsonPath("$.item.quantity").value(1))
                .andExpect(jsonPath("$.item.optionLabel").doesNotExist())
                .andExpect(jsonPath("$.item.thumbnailUrl").doesNotExist());
    }

    @Test
    @DisplayName("T3 교환: 품목 옵션이 교환 후 옵션이어도 item.optionLabel은 승인 스냅샷(originalOptionLabel)")
    void detail_exchange_usesOriginalOptionLabel() throws Exception {
        mockMvc.perform(get(CLAIMS_URL + CLAIM_EXCHANGE).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.optionLabel").value("사이즈: M"));
    }

    @Test
    @DisplayName("T4 다른 구매자: 404 불변(추가 필드가 권한 범위를 넓히지 않음)")
    void detail_otherBuyer_notFound() throws Exception {
        mockMvc.perform(get(CLAIMS_URL + CLAIM_CANCEL).headers(authHeaders.buyer(OTHER_BUYER_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.item").doesNotExist())
                .andExpect(jsonPath("$.orderNo").doesNotExist());
    }

    @Test
    @DisplayName("T5 쿼리 수: 취소 클레임 상세 1회가 상한 이내(주문·썸네일 추가는 고정 2쿼리)")
    void detail_queryCountWithinBudget() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(get(CLAIMS_URL + CLAIM_CANCEL).headers(authHeaders.buyer(BUYER_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.thumbnailUrl").value(THUMBNAIL_URL));
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        assertThat(count).isLessThanOrEqualTo(CANCEL_DETAIL_QUERY_BUDGET);
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /**
     * 주문 1건 · 품목 3개(썸네일 상품 옵션·수량 2 / 삭제 상품 옵션 없음 / 교환 완료 품목 — 현재 옵션 L·승인 스냅샷 M)와 품목마다 클레임 1건.
     */
    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6)), "
                                + "(?, ?, NOW(6), NOW(6))",
                        BUYER_USER, pid("usr_", "BCDOUSR1"), OTHER_BUYER_USER, pid("usr_", "BCDOUSR2"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '상세셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "BCDOSLR"));
                insertProduct(PRODUCT_WITH_THUMBNAIL, "썸네일상품", THUMBNAIL_URL);
                insertProduct(PRODUCT_DELETED, "삭제상품", THUMBNAIL_URL);
                insertProduct(PRODUCT_EXCHANGE, "교환상품", null);
                jdbc.update("UPDATE product SET deleted_at = NOW(6) WHERE id = ?", PRODUCT_DELETED);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', 40000, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, ORDER_PID, BUYER_USER, ORDER_NO);
                insertItem(ID_BASE, PRODUCT_WITH_THUMBNAIL, "썸네일상품", "색상: 블랙", 2);
                insertItem(ID_BASE + 1, PRODUCT_DELETED, "삭제상품", null, 1);
                insertItem(ID_BASE + 2, PRODUCT_EXCHANGE, "교환상품", "사이즈: L", 1);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                                + "requested_at, previous_order_item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'CANCEL', 'BUYER_CHANGED_MIND', 'REQUESTED', ?, NOW(6), 'PAID', NOW(6), NOW(6)), "
                                + "(?, ?, ?, 'CANCEL', 'BUYER_CHANGED_MIND', 'REQUESTED', ?, NOW(6), 'PAID', NOW(6), NOW(6))",
                        ID_BASE, CLAIM_CANCEL, ID_BASE, BUYER_USER,
                        ID_BASE + 1, CLAIM_DELETED_PRODUCT, ID_BASE + 1, BUYER_USER);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                                + "requested_at, previous_order_item_status, exchange_variant_id, original_variant_id, "
                                + "original_option_label, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'EXCHANGE', 'PRODUCT_DEFECT', 'COMPLETED', ?, NOW(6), 'DELIVERED', ?, ?, "
                                + "'사이즈: M', NOW(6), NOW(6))",
                        ID_BASE + 2, CLAIM_EXCHANGE, ID_BASE + 2, BUYER_USER, PRODUCT_EXCHANGE, PRODUCT_EXCHANGE);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertProduct(long id, String name, String thumbnailUrl) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, thumbnail_url, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SALE', 10000, ?, NOW(6), NOW(6))",
                id, pid("prd_", "BCDOPRD" + id), SELLER_ID, DUMMY_FK_ID, name, thumbnailUrl);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                        + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                id, pid("var_", "BCDOVAR" + id), id, "VCBCDO" + id, DUMMY_FK_ID);
    }

    private void insertItem(long id, long productId, String productName, String optionLabel, int quantity) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, option_label, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 10000, ?, 'PAID', ?, NOW(6), NOW(6), ?, 1000)",
                id, pid("oit_", "BCDOIT" + id), ORDER_ID, productId, productId, SELLER_ID, quantity, 10_000L * quantity,
                optionLabel, productName);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String claimPid(long id) {
        return pid("clm_", "BCDOC" + id);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

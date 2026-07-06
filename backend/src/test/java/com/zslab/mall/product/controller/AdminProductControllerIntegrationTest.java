package com.zslab.mall.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
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
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * Admin 상품 승인 endpoint E2E 통합 테스트(Track 50·실 MariaDB). HTTP → {@code AdminProductController} →
 * {@code ProductApprovalService} → 비관적 락(findByPublicIdForUpdate)·전이·DB 실 커밋을 검증한다
 * ({@code AdminSettlementControllerIntegrationTest} 패턴 준용).
 *
 * <p><b>커버</b>: T1 승인 200(PENDING→SALE)·T2 거부 200(PENDING→REJECTED)·T3 잘못된 전이 422(SALE→REJECTED)·
 * T4 멱등 approve(이미 SALE)·T5 멱등 reject(이미 REJECTED)·T6 404(미존재)·T7 403(비ADMIN).
 *
 * <p><b>트랜잭션·트랩 방지</b>: 상품 seed는 FK_CHECKS=0으로 INSERT하되 부모(category·seller)를 실제 완비한다 — 전이 UPDATE는
 * 앱 커넥션(FK_CHECKS=1)에서 실행되므로 부모 부재 시 FK 검증에 걸린다(Track 49 트랩 재발 방지·coincidental test 금지).
 * 클래스 {@code @Transactional} 없음(실 커밋 구동)·시드/정리는 {@link TransactionTemplate}+FK_CHECKS 토글·검증은 {@link JdbcTemplate}.
 */
@AutoConfigureMockMvc
class AdminProductControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9500L;         // JWT 액터(created_by 미사용·DB 행 불요)
    private static final long BUYER_ID = 9501L;         // 비ADMIN 403 확인용
    private static final long SELLER_ID = 9500L;
    private static final long CATEGORY_ID = 9500L;
    private static final long PRODUCT_ID = 9500L;
    private static final String PRODUCT_PID = pid("prd_", "TRK50PRODUCT");
    private static final String MISSING_PID = pid("prd_", "MISSINGNONE");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 승인: PENDING 상품 + ADMIN → 200·status SALE(DB 재조회)")
    void approve_pending_returns200_sale() throws Exception {
        seedProduct("PENDING");

        mockMvc.perform(post(approveUrl(PRODUCT_PID)).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPublicId").value(PRODUCT_PID))
                .andExpect(jsonPath("$.status").value("SALE"));

        assertThat(currentStatus()).isEqualTo("SALE");
    }

    @Test
    @DisplayName("T2 거부: PENDING 상품 + ADMIN → 200·status REJECTED(DB 재조회)")
    void reject_pending_returns200_rejected() throws Exception {
        seedProduct("PENDING");

        mockMvc.perform(post(rejectUrl(PRODUCT_PID)).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPublicId").value(PRODUCT_PID))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(currentStatus()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("T3 잘못된 전이: SALE 상품에 거부 → 422 PRODUCT_INVALID_STATE·status 불변")
    void reject_sale_returns422() throws Exception {
        seedProduct("SALE");

        mockMvc.perform(post(rejectUrl(PRODUCT_PID)).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_INVALID_STATE"));

        assertThat(currentStatus()).isEqualTo("SALE");
    }

    @Test
    @DisplayName("T4 멱등 승인: 이미 SALE 상품에 승인 → 200·SALE 유지")
    void approve_alreadySale_isIdempotent() throws Exception {
        seedProduct("SALE");

        mockMvc.perform(post(approveUrl(PRODUCT_PID)).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SALE"));

        assertThat(currentStatus()).isEqualTo("SALE");
    }

    @Test
    @DisplayName("T5 멱등 거부: 이미 REJECTED 상품에 거부 → 200·REJECTED 유지")
    void reject_alreadyRejected_isIdempotent() throws Exception {
        seedProduct("REJECTED");

        mockMvc.perform(post(rejectUrl(PRODUCT_PID)).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(currentStatus()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("T6 미존재: 없는 publicId 승인 → 404 PRODUCT_NOT_FOUND")
    void approve_missing_returns404() throws Exception {
        mockMvc.perform(post(approveUrl(MISSING_PID)).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("T7 비ADMIN: BUYER 토큰 승인 → 403")
    void approve_nonAdmin_returns403() throws Exception {
        seedProduct("PENDING");

        mockMvc.perform(post(approveUrl(PRODUCT_PID)).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isForbidden());

        assertThat(currentStatus()).isEqualTo("PENDING");  // 인가 차단으로 전이 미발생
    }

    // ---------- seed·helpers ----------
    // 모든 시드 INSERT는 바인딩 파라미터 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedProduct(String productStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '트랙50카테고리', 0, 0, NOW(6), NOW(6))", CATEGORY_ID);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, "
                                + "created_at, updated_at) VALUES (?, ?, '승인셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "TRK50SELLER"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙50상품', ?, 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, PRODUCT_PID, SELLER_ID, CATEGORY_ID, productStatus);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String currentStatus() {
        return jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, PRODUCT_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM category WHERE id = ?", CATEGORY_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String approveUrl(String publicId) {
        return "/api/v1/admin/products/" + publicId + "/approve";
    }

    private static String rejectUrl(String publicId) {
        return "/api/v1/admin/products/" + publicId + "/reject";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

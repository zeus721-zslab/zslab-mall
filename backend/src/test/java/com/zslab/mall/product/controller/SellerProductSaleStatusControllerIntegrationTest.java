package com.zslab.mall.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.policy.ProductPurchasePolicy;
import com.zslab.mall.product.policy.PurchaseBlockReason;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 판매 상태·수동 품절 셀프 전환 통합 테스트(Track 96-5·D-206·실 MariaDB·HTTP 경유).
 *
 * <p><b>시드 그래프</b>: 셀러 A(user A OWNER·user S STAFF·상태 파라미터)·셀러 B(user B OWNER·ACTIVE) · 카테고리 C /
 * 셀러 A 상품 — P_SALE(SALE) · P_ADMIN(STOPPED·관리자 중지) · P_SELLER(STOPPED·셀러 중지) · P_PENDING · P_REJECTED · P_SOLDOUT(SALE·관리자가 켠 수동 품절) /
 * 셀러 B 상품 — PB(SALE).
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerProductSaleStatusControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/v1/seller/products";

    private static final long USER_A = 9650L;
    private static final long USER_S = 9651L;
    private static final long USER_B = 9652L;
    private static final long BUYER_ID = 9653L;
    private static final long SELLER_A = 9650L;
    private static final long SELLER_B = 9651L;
    private static final long CATEGORY = 9650L;
    private static final long P_SALE = 9650L;
    private static final long P_ADMIN = 9651L;
    private static final long P_SELLER = 9652L;
    private static final long P_PENDING = 9653L;
    private static final long P_REJECTED = 9654L;
    private static final long P_SOLDOUT = 9655L;
    private static final long PB = 9656L;
    private static final long VARIANT_SALE = 9650L;

    private static final String P_SALE_PID = pid("SSSPSALE");
    private static final String P_ADMIN_PID = pid("SSSPADMIN");
    private static final String P_SELLER_PID = pid("SSSPSELLER");
    private static final String P_PENDING_PID = pid("SSSPPEND");
    private static final String P_REJECTED_PID = pid("SSSPREJ");
    private static final String P_SOLDOUT_PID = pid("SSSPSOLD");
    private static final String PB_PID = pid("SSSPB");
    private static final String VARIANT_SALE_PID = "var_" + padTag("SSSVSALE");
    private static final String MISSING_PID = pid("SSSPNONE");

    private static final String STOP_BODY = "{\"status\":\"STOPPED\"}";
    private static final String RESUME_BODY = "{\"status\":\"SALE\"}";
    private static final String SOLDOUT_ON_BODY = "{\"soldOut\":true}";
    private static final String SOLDOUT_OFF_BODY = "{\"soldOut\":false}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductVariantRepository productVariantRepository;

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

    // ==================== 인가·상태 가드 ====================

    @Test
    @DisplayName("T1 인가: 비인증 401 · 구매자 403 · 관리자 403 → 2 API 전부·DB 불변")
    void authorization() throws Exception {
        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(soldOutUrl(P_SALE_PID)).contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_ON_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(soldOutUrl(P_SALE_PID)).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_ON_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).headers(authHeaders.admin(USER_B))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isForbidden());

        assertProduct(P_SALE, "SALE", null, false);
        assertAuditCount(P_SALE, 0);
    }

    @Test
    @DisplayName("T2 D-190: SUSPENDED 셀러 → 403 SELLER_SUSPENDED·DB 불변")
    void suspendedSeller_returns403() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);

        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_SUSPENDED"));
        mockMvc.perform(patch(soldOutUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_ON_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_SUSPENDED"));

        assertProduct(P_SALE, "SALE", null, false);
    }

    @Test
    @DisplayName("T3 소유: 타 셀러 상품·미존재 → 404 PRODUCT_NOT_FOUND(존재 은닉)·DB 불변")
    void otherSellerOrMissing_returns404() throws Exception {
        mockMvc.perform(post(saleStatusUrl(PB_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(patch(soldOutUrl(PB_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_ON_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(post(saleStatusUrl(MISSING_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isNotFound());

        assertProduct(PB, "SALE", null, false);
    }

    // ==================== 판매 상태 전이 ====================

    @Test
    @DisplayName("T4 판매중지: SALE + 셀러 → 200·status STOPPED·source SELLER·응답 saleStopSource·감사 1행(SELLER)")
    void stop_saleBySeller_returns200() throws Exception {
        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPublicId").value(P_SALE_PID))
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.saleStopSource").value("SELLER"));

        assertProduct(P_SALE, "STOPPED", "SELLER", false);
        assertAuditCount(P_SALE, 1);
        Map<String, Object> audit = latestAudit(P_SALE);
        assertThat(audit.get("actor_role")).isEqualTo("SELLER");
        assertThat(((Number) audit.get("actor_user_id")).longValue()).isEqualTo(USER_A);
        assertThat((String) audit.get("diff_json")).contains("\"status\"").contains("\"STOPPED\"").contains("\"saleStopSource\"")
                .contains("\"SELLER\"");
    }

    @Test
    @DisplayName("T5 재판매: STOPPED(SELLER) + 셀러 → 200·status SALE·source NULL·응답 saleStopSource 부재(non_null)")
    void resume_sellerStopped_returns200() throws Exception {
        mockMvc.perform(post(saleStatusUrl(P_SELLER_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(RESUME_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SALE"))
                .andExpect(jsonPath("$.saleStopSource").doesNotExist());

        assertProduct(P_SELLER, "SALE", null, false);
        assertAuditCount(P_SELLER, 1);
    }

    @Test
    @DisplayName("T6 제재 우회 차단: STOPPED(ADMIN) + 셀러 재판매 → 422 PRODUCT_STOPPED_BY_ADMIN·행 불변·감사 0")
    void resume_adminStopped_returns422() throws Exception {
        mockMvc.perform(post(saleStatusUrl(P_ADMIN_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(RESUME_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_STOPPED_BY_ADMIN"));

        assertProduct(P_ADMIN, "STOPPED", "ADMIN", false);
        assertAuditCount(P_ADMIN, 0);
    }

    @Test
    @DisplayName("T7 허용 외 전이: PENDING·REJECTED 상품에 STOPPED/SALE 요청 · 같은 상태 재요청 → 422 PRODUCT_INVALID_STATE·불변")
    void invalidTransitions_return422() throws Exception {
        for (String body : new String[] {STOP_BODY, RESUME_BODY}) {
            mockMvc.perform(post(saleStatusUrl(P_PENDING_PID)).headers(authHeaders.seller(USER_A))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PRODUCT_INVALID_STATE"));
            mockMvc.perform(post(saleStatusUrl(P_REJECTED_PID)).headers(authHeaders.seller(USER_A))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PRODUCT_INVALID_STATE"));
        }
        // 같은 상태 재요청(SALE→SALE·STOPPED→STOPPED)은 관리자와 같이 422(오조작 감지).
        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(RESUME_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_INVALID_STATE"));
        mockMvc.perform(post(saleStatusUrl(P_SELLER_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_INVALID_STATE"));

        assertProduct(P_PENDING, "PENDING", null, false);
        assertProduct(P_REJECTED, "REJECTED", null, false);
        assertProduct(P_SALE, "SALE", null, false);
        assertProduct(P_SELLER, "STOPPED", "SELLER", false);
    }

    @Test
    @DisplayName("T8 허용 외 값: status HIDDEN → 400 VALIDATION_FAILED · soldOut 누락 → 400")
    void invalidBody_returns400() throws Exception {
        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch(soldOutUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertProduct(P_SALE, "SALE", null, false);
    }

    @Test
    @DisplayName("T9 역할 무차등(D4 α): SELLER_STAFF 구성원도 판매중지 200")
    void stop_byStaff_returns200() throws Exception {
        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).headers(authHeaders.seller(USER_S))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));

        assertProduct(P_SALE, "STOPPED", "SELLER", false);
        assertThat(((Number) latestAudit(P_SALE).get("actor_user_id")).longValue()).isEqualTo(USER_S);
    }

    // ==================== 수동 품절 ====================

    @Test
    @DisplayName("T10 품절 on/off: 셀러 토글 on → 200·soldoutManual true·감사 / off → false / 같은 값 재요청 200·감사 skip")
    void soldOut_toggle() throws Exception {
        mockMvc.perform(patch(soldOutUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_ON_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldoutManual").value(true))
                .andExpect(jsonPath("$.status").value("SALE"));
        assertProduct(P_SALE, "SALE", null, true);
        assertAuditCount(P_SALE, 1);
        assertThat((String) latestAudit(P_SALE).get("diff_json")).contains("\"soldoutManual\"");
        // 구매 정책 반영: 상품 단위 수동 품절은 ProductPurchasePolicy.saleBlock에서 SOLD_OUT(variant SALE·재고 무관).
        assertThat(saleBlockOfSaleProduct()).contains(PurchaseBlockReason.SOLD_OUT);

        mockMvc.perform(patch(soldOutUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_OFF_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldoutManual").value(false));
        assertProduct(P_SALE, "SALE", null, false);
        assertAuditCount(P_SALE, 2);
        assertThat(saleBlockOfSaleProduct()).isEmpty();

        // 같은 값 재요청은 no-op(엔티티 계약)·변경 없음이라 감사도 skip.
        mockMvc.perform(patch(soldOutUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_OFF_BODY))
                .andExpect(status().isOk());
        assertAuditCount(P_SALE, 2);
    }

    @Test
    @DisplayName("T11 D3 α: 관리자가 켠 수동 품절도 셀러가 해제 가능(품절은 제재 아님) → 200·false")
    void soldOut_adminSet_sellerCanClear() throws Exception {
        assertProduct(P_SOLDOUT, "SALE", null, true);

        mockMvc.perform(patch(soldOutUrl(P_SOLDOUT_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_OFF_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldoutManual").value(false));

        assertProduct(P_SOLDOUT, "SALE", null, false);
    }

    @Test
    @DisplayName("T12 품절은 상태와 독립: STOPPED(ADMIN) 상품의 품절 토글도 200(status·source 불변)")
    void soldOut_onStoppedProduct_keepsStatus() throws Exception {
        mockMvc.perform(patch(soldOutUrl(P_ADMIN_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_ON_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.saleStopSource").value("ADMIN"))
                .andExpect(jsonPath("$.soldoutManual").value(true));

        assertProduct(P_ADMIN, "STOPPED", "ADMIN", true);
    }

    @Test
    @DisplayName("T14 보정: 셀러 중지 상품을 관리자가 중지 요청(제재 전환) → 200·status 유지·source ADMIN → 이후 셀러 재판매 422 PRODUCT_STOPPED_BY_ADMIN·불변")
    void adminEscalation_blocksSellerResume() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products/" + P_SELLER_PID + "/sale-status").headers(authHeaders.admin(USER_B))
                        .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
        assertProduct(P_SELLER, "STOPPED", "ADMIN", false);

        mockMvc.perform(post(saleStatusUrl(P_SELLER_PID)).headers(authHeaders.seller(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(RESUME_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_STOPPED_BY_ADMIN"));
        assertProduct(P_SELLER, "STOPPED", "ADMIN", false);
        // 감사: 전환 1행(ADMIN)만 — 셀러 422는 적재 없음.
        assertAuditCount(P_SELLER, 1);
        assertThat(latestAudit(P_SELLER).get("actor_role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("T15 보정: 셀러가 STOPPED 상품(ADMIN·SELLER 모두)에 중지 재요청 → 422 PRODUCT_INVALID_STATE·source 불변(ADMIN → SELLER 전환 수단 없음)")
    void sellerStop_onStopped_neverChangesSource() throws Exception {
        for (String[] target : new String[][] {{P_ADMIN_PID, "ADMIN"}, {P_SELLER_PID, "SELLER"}}) {
            mockMvc.perform(post(saleStatusUrl(target[0])).headers(authHeaders.seller(USER_A))
                            .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PRODUCT_INVALID_STATE"));
        }
        assertProduct(P_ADMIN, "STOPPED", "ADMIN", false);
        assertProduct(P_SELLER, "STOPPED", "SELLER", false);
        assertAuditCount(P_ADMIN, 0);
        assertAuditCount(P_SELLER, 0);
    }

    // ==================== 불변식 ====================

    @Test
    @DisplayName("T13 불변식: 전 경로(중지·재판매·422·품절) 실행 후 시드 상품 전건 STOPPED ↔ sale_stop_source NOT NULL 위반 0")
    void invariant_stoppedIffSourceNotNull() throws Exception {
        mockMvc.perform(post(saleStatusUrl(P_SALE_PID)).headers(authHeaders.seller(USER_A))
                .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY)).andExpect(status().isOk());
        mockMvc.perform(post(saleStatusUrl(P_SELLER_PID)).headers(authHeaders.seller(USER_A))
                .contentType(MediaType.APPLICATION_JSON).content(RESUME_BODY)).andExpect(status().isOk());
        mockMvc.perform(post(saleStatusUrl(P_ADMIN_PID)).headers(authHeaders.seller(USER_A))
                .contentType(MediaType.APPLICATION_JSON).content(RESUME_BODY)).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post(saleStatusUrl(P_PENDING_PID)).headers(authHeaders.seller(USER_A))
                .contentType(MediaType.APPLICATION_JSON).content(STOP_BODY)).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch(soldOutUrl(P_SOLDOUT_PID)).headers(authHeaders.seller(USER_A))
                .contentType(MediaType.APPLICATION_JSON).content(SOLDOUT_OFF_BODY)).andExpect(status().isOk());

        Long violations = jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id BETWEEN ? AND ? "
                + "AND ((status = 'STOPPED') <> (sale_stop_source IS NOT NULL))", Long.class, P_SALE, PB);
        assertThat(violations).isZero();
    }

    // ==================== helpers ====================

    /** P_SALE + VARIANT_SALE 조합의 판매 상태 판정(재고 제외·ProductPurchasePolicy 단일 소스). */
    private java.util.Optional<PurchaseBlockReason> saleBlockOfSaleProduct() {
        Product product = productRepository.findById(P_SALE).orElseThrow();
        ProductVariant variant = productVariantRepository.findById(VARIANT_SALE).orElseThrow();
        return ProductPurchasePolicy.saleBlock(product, variant, LocalDateTime.now());
    }

    private void assertProduct(long productId, String status, String source, boolean soldOut) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, sale_stop_source, is_soldout_manual FROM product WHERE id = ?", productId);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("sale_stop_source")).isEqualTo(source);
        // TINYINT(1)은 드라이버가 Boolean으로 매핑한다.
        assertThat(row.get("is_soldout_manual")).isEqualTo(soldOut);
    }

    private void assertAuditCount(long productId, int expected) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'PRODUCT' AND target_id = ?",
                Long.class, productId);
        assertThat(count).isEqualTo((long) expected);
    }

    private Map<String, Object> latestAudit(long productId) {
        return jdbc.queryForMap("SELECT actor_user_id, actor_role, action, diff_json FROM audit_log "
                + "WHERE target_type = 'PRODUCT' AND target_id = ? ORDER BY id DESC LIMIT 1", productId);
    }

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedUser(USER_A, "SSSUSA");
                seedUser(USER_S, "SSSUSS");
                seedUser(USER_B, "SSSUSB");
                seedSeller(SELLER_A, "SSSSLA", "판매상태셀러A", sellerAStatus);
                seedSeller(SELLER_B, "SSSSLB", "판매상태셀러B", SellerStatus.ACTIVE);
                seedMembership(USER_A, SELLER_A, "SELLER_OWNER");
                seedMembership(USER_S, SELLER_A, "SELLER_STAFF");
                seedMembership(USER_B, SELLER_B, "SELLER_OWNER");
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '판매상태카테고리', 0, 0, NOW(6), NOW(6))", CATEGORY);

                seedProduct(P_SALE, P_SALE_PID, SELLER_A, "판매중상품", "SALE", null, false);
                seedProduct(P_ADMIN, P_ADMIN_PID, SELLER_A, "관리자중지상품", "STOPPED", "ADMIN", false);
                seedProduct(P_SELLER, P_SELLER_PID, SELLER_A, "셀러중지상품", "STOPPED", "SELLER", false);
                seedProduct(P_PENDING, P_PENDING_PID, SELLER_A, "승인대기상품", "PENDING", null, false);
                seedProduct(P_REJECTED, P_REJECTED_PID, SELLER_A, "반려상품", "REJECTED", null, false);
                seedProduct(P_SOLDOUT, P_SOLDOUT_PID, SELLER_A, "품절상품", "SALE", null, true);
                seedProduct(PB, PB_PID, SELLER_B, "타셀러상품", "SALE", null, false);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VC-SALE', 0, 'SALE', 0, 0, 1, NOW(6), NOW(6))", VARIANT_SALE, VARIANT_SALE_PID, P_SALE);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedUser(long userId, String tag) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, "usr_" + padTag(tag));
    }

    private void seedSeller(long sellerId, String tag, String companyName, SellerStatus status) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, "slr_" + padTag(tag), companyName, status.name());
    }

    private void seedMembership(long userId, long sellerId, String roleCode) {
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = ?", userId, sellerId, roleCode);
    }

    private void seedProduct(long id, String publicId, long sellerId, String name, String status, String source, boolean soldOut) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, sale_stop_source, is_soldout_manual, "
                + "base_price, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 10000, NOW(6), NOW(6))",
                id, publicId, sellerId, CATEGORY, name, status, source, soldOut ? 1 : 0);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'PRODUCT' AND target_id BETWEEN ? AND ?", P_SALE, PB);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_SALE);
                jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", P_SALE, PB);
                jdbc.update("DELETE FROM category WHERE id = ?", CATEGORY);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?, ?)", USER_A, USER_S, USER_B);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?)", USER_A, USER_S, USER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String saleStatusUrl(String publicId) {
        return BASE_URL + "/" + publicId + "/sale-status";
    }

    private static String soldOutUrl(String publicId) {
        return BASE_URL + "/" + publicId + "/soldout";
    }

    private static String pid(String tag) {
        return "prd_" + padTag(tag);
    }

    private static String padTag(String tag) {
        return (tag + "00000000000000000000000000").substring(0, 26);
    }
}

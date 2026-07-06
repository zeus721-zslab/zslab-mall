package com.zslab.mall.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.product.controller.request.AddProductImageRequest;
import com.zslab.mall.product.controller.request.ReorderProductImagesRequest;
import com.zslab.mall.product.controller.response.ProductImageResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 셀러 상품 이미지 관리 endpoint E2E 통합 테스트(Track 59 BL-6·실 MariaDB). HTTP → {@code SellerProductImageController} →
 * {@code SellerProductImageService} → product_image 그래프 흐름을 실 커밋·HTTP 경유로 실측한다
 * ({@code ProductRegistrationControllerIntegrationTest} 픽스처 패턴 정합). 등록(append displayOrder)·대표 단일성
 * (demote-then-set)·정렬 재배치·soft delete·2-hop 소유권(404 은닉)·thumbnail_url 독립을 회귀 잠근다.
 *
 * <p><b>seed</b>: 판매자 2인(A·B)의 user·seller·seller_user(SELLER_OWNER)·category·product를 실 seed해
 * {@code SellerActorResolver}(user_id→seller_id)와 2-hop 소유권을 실측한다. product_image는 각 테스트가 API 또는
 * 직접 seed로 구성한다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02)로 하며, 커밋 검증을
 * JdbcTemplate 직접 조회로 하므로 클래스에 {@code @Transactional}을 두지 않는다.
 */
@AutoConfigureMockMvc
class SellerProductImageControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long SELLER_A_ID = 9640L;
    private static final long SELLER_A_USER_ID = 9641L;
    private static final long SELLER_B_ID = 9650L;
    private static final long SELLER_B_USER_ID = 9651L;
    private static final long BUYER_USER_ID = 9642L; // 비-SELLER role 403 검증용(user seed 불요·필터 단계 차단)
    private static final long CATEGORY_ID = 9643L;
    private static final long PRODUCT_A_ID = 9644L; // seller A 소유
    private static final long PRODUCT_B_ID = 9645L; // seller B 소유

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== 등록·append ====================

    @Test
    @DisplayName("T1 최초 이미지 등록(main=false) → 201 + displayOrder=0·is_main=0")
    void add_firstImage_returns201_displayOrderZero() throws Exception {
        ProductImageResponse image = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/a.jpg", false);

        assertThat(image.displayOrder()).isZero();
        assertThat(image.main()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM product_image WHERE id=? AND product_id=? AND display_order=0 AND is_main=0",
                image.id(), PRODUCT_A_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 main=true 등록 → is_main=1 + product.thumbnail_url 무동기화(독립·결정3)")
    void add_mainTrue_setsMain_doesNotSyncThumbnail() throws Exception {
        ProductImageResponse image = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/main.jpg", true);

        assertThat(image.main()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM product_image WHERE id=? AND is_main=1", image.id())).isEqualTo(1);
        // 대표 지정이 thumbnail_url을 건드리지 않음(seed NULL 유지) — is_main↔thumbnail_url 독립 실증.
        assertThat(jdbc.queryForObject("SELECT thumbnail_url FROM product WHERE id=?", String.class, PRODUCT_A_ID)).isNull();
    }

    @Test
    @DisplayName("T3 기존 N개 있을 때 append → displayOrder=N 순차 증가(0·1·2)")
    void add_appendsAtEnd_incrementingDisplayOrder() throws Exception {
        ProductImageResponse first = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/1.jpg", false);
        ProductImageResponse second = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/2.jpg", false);
        ProductImageResponse third = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/3.jpg", false);

        assertThat(first.displayOrder()).isZero();
        assertThat(second.displayOrder()).isEqualTo(1);
        assertThat(third.displayOrder()).isEqualTo(2);
    }

    // ==================== 대표 단일성(demote-then-set) ====================

    @Test
    @DisplayName("T4 이미지 3개 순차 대표 지정 → 항상 활성 대표 정확히 1개·직전 대표 강등(단일성 실검증)")
    void designateMain_sequential_keepsExactlyOneMain() throws Exception {
        Long img1 = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/1.jpg", false).id();
        Long img2 = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/2.jpg", false).id();
        Long img3 = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/3.jpg", false).id();

        designateMain(sellerAAuth(), PRODUCT_A_ID, img1).andExpect(status().isOk());
        assertThat(mainCount(PRODUCT_A_ID)).isEqualTo(1);
        assertThat(isMain(img1)).isTrue();

        designateMain(sellerAAuth(), PRODUCT_A_ID, img2).andExpect(status().isOk());
        assertThat(mainCount(PRODUCT_A_ID)).isEqualTo(1);
        assertThat(isMain(img2)).isTrue();
        assertThat(isMain(img1)).isFalse(); // 직전 대표 강등

        designateMain(sellerAAuth(), PRODUCT_A_ID, img3).andExpect(status().isOk());
        assertThat(mainCount(PRODUCT_A_ID)).isEqualTo(1);
        assertThat(isMain(img3)).isTrue();
        assertThat(isMain(img2)).isFalse();
    }

    @Test
    @DisplayName("T5 이미 대표인 이미지 재지정 → 200·멱등(여전히 대표 1개)")
    void designateMain_alreadyMain_isIdempotent() throws Exception {
        Long img1 = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/1.jpg", false).id();

        designateMain(sellerAAuth(), PRODUCT_A_ID, img1).andExpect(status().isOk());
        designateMain(sellerAAuth(), PRODUCT_A_ID, img1).andExpect(status().isOk());

        assertThat(mainCount(PRODUCT_A_ID)).isEqualTo(1);
        assertThat(isMain(img1)).isTrue();
    }

    // ==================== 정렬 재배치 ====================

    @Test
    @DisplayName("T6 reorder → imageIds 순서대로 display_order 0..n-1 재배치")
    void reorder_reassignsDisplayOrder() throws Exception {
        seedImage(7101L, PRODUCT_A_ID, "https://cdn/1.jpg", 0, false);
        seedImage(7102L, PRODUCT_A_ID, "https://cdn/2.jpg", 1, false);
        seedImage(7103L, PRODUCT_A_ID, "https://cdn/3.jpg", 2, false);

        reorder(sellerAAuth(), PRODUCT_A_ID, List.of(7103L, 7101L, 7102L)).andExpect(status().isOk());

        assertThat(displayOrder(7103L)).isZero();
        assertThat(displayOrder(7101L)).isEqualTo(1);
        assertThat(displayOrder(7102L)).isEqualTo(2);
    }

    @Test
    @DisplayName("T7 reorder imageIds 불일치(누락·과잉·중복) → 400 MALFORMED_REQUEST")
    void reorder_idsMismatch_returns400() throws Exception {
        seedImage(7201L, PRODUCT_A_ID, "https://cdn/1.jpg", 0, false);
        seedImage(7202L, PRODUCT_A_ID, "https://cdn/2.jpg", 1, false);
        seedImage(7203L, PRODUCT_A_ID, "https://cdn/3.jpg", 2, false);

        reorder(sellerAAuth(), PRODUCT_A_ID, List.of(7201L, 7202L)) // 누락
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        reorder(sellerAAuth(), PRODUCT_A_ID, List.of(7201L, 7202L, 7203L, 999999L)) // 과잉(미존재 id)
                .andExpect(status().isBadRequest());
        reorder(sellerAAuth(), PRODUCT_A_ID, List.of(7201L, 7201L, 7202L)) // 중복 참조
                .andExpect(status().isBadRequest());
    }

    // ==================== 삭제(soft) ====================

    @Test
    @DisplayName("T8 삭제 → 204 + deleted_at 마킹·활성 조회 제외(@SQLRestriction)")
    void delete_softDeletes_excludedFromActive() throws Exception {
        Long img1 = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/1.jpg", false).id();
        Long img2 = add(sellerAAuth(), PRODUCT_A_ID, "https://cdn/2.jpg", false).id();

        mockMvc.perform(delete(base(PRODUCT_A_ID) + "/" + img1).headers(sellerAAuth()))
                .andExpect(status().isNoContent());

        // 행은 남되 deleted_at 마킹(soft) + 활성(deleted_at IS NULL) 집합에서 제외
        assertThat(count("SELECT COUNT(*) FROM product_image WHERE id=? AND deleted_at IS NOT NULL", img1)).isEqualTo(1);
        assertThat(activeCount(PRODUCT_A_ID)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product_image WHERE id=? AND deleted_at IS NULL", img2)).isEqualTo(1);
    }

    // ==================== 2-hop 소유권(404 은닉) ====================

    @Test
    @DisplayName("T9 타 판매자 상품에 이미지 등록 시도 → 404 PRODUCT_NOT_FOUND(2-hop product 스코프)")
    void add_crossTenantProduct_returns404() throws Exception {
        mockMvc.perform(post(base(PRODUCT_A_ID)).headers(sellerBAuth()) // B가 A의 상품에
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddProductImageRequest("https://cdn/x.jpg", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("T10 타 판매자 이미지 대표지정·삭제 시도 → 404 PRODUCT_IMAGE_NOT_FOUND(2-hop image 스코프)")
    void mutateCrossTenantImage_returns404() throws Exception {
        seedImage(7301L, PRODUCT_A_ID, "https://cdn/a.jpg", 0, false); // A 소유 이미지

        designateMain(sellerBAuth(), PRODUCT_A_ID, 7301L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_NOT_FOUND"));
        mockMvc.perform(delete(base(PRODUCT_A_ID) + "/7301").headers(sellerBAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_NOT_FOUND"));
        // 은닉 확인: A 이미지는 무변경(대표 아님·미삭제)
        assertThat(isMain(7301L)).isFalse();
        assertThat(count("SELECT COUNT(*) FROM product_image WHERE id=7301 AND deleted_at IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("T11 존재하지 않는 imageId 대표지정 → 404 PRODUCT_IMAGE_NOT_FOUND")
    void designateMain_unknownImage_returns404() throws Exception {
        designateMain(sellerAAuth(), PRODUCT_A_ID, 888888L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_NOT_FOUND"));
    }

    // ==================== 인증·검증 ====================

    @Test
    @DisplayName("T12 미인증(토큰 없음) → 401 UNAUTHENTICATED")
    void add_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(base(PRODUCT_A_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddProductImageRequest("https://cdn/x.jpg", false))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("T13 비-SELLER role(BUYER 토큰) → 403 FORBIDDEN")
    void add_buyerRole_returns403() throws Exception {
        mockMvc.perform(post(base(PRODUCT_A_ID)).headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddProductImageRequest("https://cdn/x.jpg", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("T14 imageUrl 공백 → 400 VALIDATION_FAILED(@NotBlank)")
    void add_blankImageUrl_returns400() throws Exception {
        mockMvc.perform(post(base(PRODUCT_A_ID)).headers(sellerAAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddProductImageRequest("  ", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ==================== helpers ====================

    private HttpHeaders sellerAAuth() {
        return authHeaders.seller(SELLER_A_USER_ID);
    }

    private HttpHeaders sellerBAuth() {
        return authHeaders.seller(SELLER_B_USER_ID);
    }

    private static String base(long productId) {
        return "/api/v1/seller/products/" + productId + "/images";
    }

    private ProductImageResponse add(HttpHeaders headers, long productId, String imageUrl, boolean main) throws Exception {
        MvcResult result = mockMvc.perform(post(base(productId)).headers(headers)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddProductImageRequest(imageUrl, main))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ProductImageResponse.class);
    }

    private org.springframework.test.web.servlet.ResultActions designateMain(
            HttpHeaders headers, long productId, long imageId) throws Exception {
        return mockMvc.perform(patch(base(productId) + "/" + imageId + "/main").headers(headers));
    }

    private org.springframework.test.web.servlet.ResultActions reorder(
            HttpHeaders headers, long productId, List<Long> imageIds) throws Exception {
        return mockMvc.perform(patch(base(productId) + "/reorder").headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReorderProductImagesRequest(imageIds))));
    }

    private boolean isMain(long imageId) {
        return count("SELECT COUNT(*) FROM product_image WHERE id=? AND is_main=1", imageId) == 1;
    }

    private int mainCount(long productId) {
        return count("SELECT COUNT(*) FROM product_image WHERE product_id=? AND is_main=1 AND deleted_at IS NULL", productId);
    }

    private int activeCount(long productId) {
        return count("SELECT COUNT(*) FROM product_image WHERE product_id=? AND deleted_at IS NULL", productId);
    }

    private int displayOrder(long imageId) {
        Integer order = jdbc.queryForObject("SELECT display_order FROM product_image WHERE id=?", Integer.class, imageId);
        return order == null ? -1 : order;
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    // 모든 시드/조회는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seedImage(long id, long productId, String url, int displayOrder, boolean main) {
        tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO product_image (id, product_id, image_url, display_order, is_main, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, productId, url, displayOrder, main ? 1 : 0));
    }

    private void seedFixtures() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSeller(SELLER_A_ID, SELLER_A_USER_ID, "PRIA");
                seedSeller(SELLER_B_ID, SELLER_B_USER_ID, "PRIB");
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '이미지테스트카테고리', 0, 0, NOW(6), NOW(6))", CATEGORY_ID);
                seedProduct(PRODUCT_A_ID, SELLER_A_ID, "PRIPA");
                seedProduct(PRODUCT_B_ID, SELLER_B_ID, "PRIPB");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSeller(long sellerId, long userId, String tag) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", tag + "U"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '이미지테스트셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                sellerId, pid("slr_", tag + "S"));
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                        + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                userId, sellerId);
    }

    private void seedProduct(long productId, long sellerId, String tag) {
        // thumbnail_url은 명시적으로 NULL(is_main↔thumbnail 독립 검증 기준선). status는 소유권과 무관(SALE 고정).
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '이미지테스트상품', 'SALE', 10000, NOW(6), NOW(6))",
                productId, pid("prd_", tag), sellerId, CATEGORY_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM product_image WHERE product_id IN (?, ?)", PRODUCT_A_ID, PRODUCT_B_ID);
                jdbc.update("DELETE FROM product WHERE id IN (?, ?)", PRODUCT_A_ID, PRODUCT_B_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", SELLER_A_USER_ID, SELLER_B_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A_ID, SELLER_B_ID);
                jdbc.update("DELETE FROM category WHERE id=?", CATEGORY_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", SELLER_A_USER_ID, SELLER_B_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

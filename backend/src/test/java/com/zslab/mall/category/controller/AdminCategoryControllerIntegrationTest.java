package com.zslab.mall.category.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.category.controller.response.CreateCategoryResponse;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.product.controller.request.ProductRegistrationRequest;
import com.zslab.mall.product.controller.request.ProductVariantRequest;
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
 * 카테고리 생성 endpoint E2E 통합 테스트(Track 46·실 MariaDB). HTTP → {@code AdminCategoryController} →
 * {@code CategoryService} → category DB 흐름을 실 커밋·HTTP 경유로 실측한다({@code AdminSellerControllerIntegrationTest}
 * 픽스처 패턴 정합·라이브 트랩 차단).
 *
 * <p><b>중복 가드 = DB 제약(uk_category_dedup_key·V13)</b>: 단순 UNIQUE는 MariaDB NULL 시맨틱(루트 parent_id NULL·
 * 활성 deleted_at NULL)상 무효라 STORED generated dedup_key로 우회했다. ②는 동일 displayName 재생성이 saveAndFlush
 * 위반→409로 실차단됨을 실측해 그 우회가 실제 동작함을 회귀 잠금한다.
 *
 * <p><b>체이닝(⑥)</b>: category 테이블이 비어 상품 등록이 구조적으로 불가하던 상태를 본 트랙이 해소함을 실증한다 —
 * API로 생성한 categoryId로 상품 등록이 201로 성공하고 product.category_id가 그 값과 일치하는지 확인한다.
 *
 * <p><b>트랜잭션</b>: 생성 커밋을 JdbcTemplate 직접 조회로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다.
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)로 한다.
 */
@AutoConfigureMockMvc
class AdminCategoryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/categories";
    private static final String PRODUCT_URL = "/api/v1/seller/products";

    private static final long ADMIN_ID = 4601L;         // ADMIN 액터(JWT subject·hasRole ADMIN 통과)
    private static final long NON_ADMIN_USER = 4602L;   // BUYER 토큰 subject(인가 거부 403)
    private static final long SELLER_ID = 4610L;        // 체이닝 상품 등록용 seller
    private static final long SELLER_USER_ID = 4611L;   // 체이닝 상품 등록 JWT subject(seller_user 매핑 소스)

    private static final String NAME_CREATE = "트랙46생성카테고리";   // ① 생성 대상
    private static final String NAME_DUP = "트랙46중복카테고리";     // ② 409 대상
    private static final String NAME_FORBIDDEN = "트랙46금지카테고리"; // ⑤ 403·미생성
    private static final String NAME_CHAIN = "트랙46체이닝카테고리";   // ⑥ 체이닝 대상

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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("① ADMIN 성공: 유효 생성 → 201·categoryId/displayName/depth=1/sortOrder 반환·category 1건 커밋")
    void create_validAdmin_returns201_persistsCategory() throws Exception {
        MvcResult result = mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(NAME_CREATE, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").exists())
                .andExpect(jsonPath("$.displayName").value(NAME_CREATE))
                .andExpect(jsonPath("$.depth").value(1))
                .andExpect(jsonPath("$.sortOrder").value(3))
                .andReturn();

        CreateCategoryResponse response = extract(result);
        // 루트 카테고리 1건이 실제 커밋됐는지(parent_id NULL·depth 1·활성) 확인
        assertThat(count("SELECT COUNT(*) FROM category WHERE id=? AND display_name=? AND parent_id IS NULL "
                + "AND depth=1 AND sort_order=3 AND deleted_at IS NULL", response.categoryId(), NAME_CREATE)).isEqualTo(1);
    }

    @Test
    @DisplayName("② 중복 displayName: 동일명 재생성 → 409 CATEGORY_DUPLICATE(uk_category_dedup_key·generated 컬럼 실동작)")
    void create_duplicateDisplayName_returns409() throws Exception {
        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(NAME_DUP, 0)))
                .andExpect(status().isCreated());

        // 동일 스코프(루트)·동일 displayName 재생성은 dedup_key 충돌 → saveAndFlush 위반 → 409
        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(NAME_DUP, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_DUPLICATE"));

        // 중복은 삽입되지 않아 활성 1건만 잔존
        assertThat(count("SELECT COUNT(*) FROM category WHERE display_name=? AND deleted_at IS NULL", NAME_DUP)).isEqualTo(1);
    }

    @Test
    @DisplayName("③ displayName 공백 → 400 VALIDATION_FAILED·미생성(@NotBlank)")
    void create_blankDisplayName_returns400() throws Exception {
        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"   \",\"sortOrder\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("④ sortOrder 음수 → 400 VALIDATION_FAILED·미생성(@PositiveOrZero)")
    void create_negativeSortOrder_returns400() throws Exception {
        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(NAME_CREATE, -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(count("SELECT COUNT(*) FROM category WHERE display_name=?", NAME_CREATE)).isZero();
    }

    @Test
    @DisplayName("⑤ 비ADMIN: BUYER 토큰 → 403 FORBIDDEN·category 미생성(SecurityConfig hasRole 인가 강제)")
    void create_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .headers(authHeaders.buyer(NON_ADMIN_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(NAME_FORBIDDEN, 0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(count("SELECT COUNT(*) FROM category WHERE display_name=?", NAME_FORBIDDEN)).isZero();
    }

    @Test
    @DisplayName("⑥ 체이닝: API로 생성한 categoryId로 상품 등록 → 201·product.category_id 일치(빈 category 해소 실증)")
    void create_thenRegisterProduct_succeeds() throws Exception {
        seedSeller();

        // 1) 카테고리 생성(ADMIN)
        MvcResult created = mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(NAME_CHAIN, 0)))
                .andExpect(status().isCreated())
                .andReturn();
        Long categoryId = extract(created).categoryId();

        // 2) 그 categoryId로 상품 등록(SELLER) → categoryId existsById 통과 → 201
        ProductRegistrationRequest product = new ProductRegistrationRequest(
                categoryId, "체이닝검증상품", null, 10000L, null,
                List.of(),
                List.of(new ProductVariantRequest("CHAIN-1", null, null, 0L, 0, 5, List.of())));

        mockMvc.perform(post(PRODUCT_URL)
                        .headers(authHeaders.seller(SELLER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productPublicId").exists());

        // 등록된 상품이 방금 만든 카테고리를 참조하는지 확인(체인 정합)
        assertThat(count("SELECT COUNT(*) FROM product WHERE seller_id=? AND category_id=?",
                SELLER_ID, categoryId)).isEqualTo(1);
    }

    // ---------- seed·helpers (AdminSellerControllerIntegrationTest 패턴·? positional 바인딩·SQL injection 없음) ----------

    private String body(String displayName, int sortOrder) {
        return "{\"displayName\":\"" + displayName + "\",\"sortOrder\":" + sortOrder + "}";
    }

    private CreateCategoryResponse extract(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), CreateCategoryResponse.class);
    }

    // 체이닝 상품 등록에 필요한 seller_user(user_id→seller_id·role SELLER_OWNER)만 시드한다.
    private void seedSeller() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        SELLER_USER_ID, pid("usr_", "T46USR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙46체이닝셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "T46SLR"));
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        SELLER_USER_ID, SELLER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // 체이닝 상품 그래프 정리(자식→부모·product 마지막)
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id IN (SELECT id FROM inventory WHERE variant_id IN "
                        + "(SELECT id FROM product_variant WHERE product_id IN (SELECT id FROM product WHERE seller_id=?)))", SELLER_ID);
                jdbc.update("DELETE FROM inventory WHERE variant_id IN "
                        + "(SELECT id FROM product_variant WHERE product_id IN (SELECT id FROM product WHERE seller_id=?))", SELLER_ID);
                jdbc.update("DELETE FROM product_variant WHERE product_id IN (SELECT id FROM product WHERE seller_id=?)", SELLER_ID);
                jdbc.update("DELETE FROM product_option_value WHERE option_group_id IN "
                        + "(SELECT id FROM product_option_group WHERE product_id IN (SELECT id FROM product WHERE seller_id=?))", SELLER_ID);
                jdbc.update("DELETE FROM product_option_group WHERE product_id IN (SELECT id FROM product WHERE seller_id=?)", SELLER_ID);
                jdbc.update("DELETE FROM product WHERE seller_id=?", SELLER_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id=?", SELLER_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id=?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id=?", SELLER_USER_ID);
                jdbc.update("DELETE FROM category WHERE display_name IN (?, ?, ?, ?)",
                        NAME_CREATE, NAME_DUP, NAME_FORBIDDEN, NAME_CHAIN);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

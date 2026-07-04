package com.zslab.mall.product.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 구매자 카탈로그 조회 endpoint E2E 통합 테스트(Track 44·실 MariaDB·MockMvc). HTTP → {@code ProductCatalogController} →
 * {@code ProductCatalogService} → Repository 조회 흐름을 실측한다({@code ProductRegistrationControllerIntegrationTest}
 * 픽스처 패턴 정합). 상태 전이 쓰기 경로가 없으므로(등록 시 전부 SALE) HIDDEN·STOPPED·SUSPENDED·TERMINATED·품절·
 * soft-delete 데이터는 JDBC로 직접 시드한다(LT-02 FOREIGN_KEY_CHECKS try-finally).
 *
 * <p>커버: D1 노출/비노출 제외(HIDDEN·STOPPED·판매자 SUSPENDED/TERMINATED·soft-delete)·카테고리 필터·4정렬·페이징·
 * D2 품절 배지·D3 대표가(basePrice+MIN additional)·DEFAULT sentinel 숨김·단건 200/404·공개(미인증) 접근.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductCatalogControllerIntegrationTest {

    private static final String URL = "/api/v1/products";

    private static final long SELLER_ACTIVE = 44001L;
    private static final long SELLER_SUSPENDED = 44002L;
    private static final long SELLER_TERMINATED = 44003L;
    private static final List<Long> SELLER_IDS = List.of(SELLER_ACTIVE, SELLER_SUSPENDED, SELLER_TERMINATED);

    private static final long CAT_EXCL = 44101L;
    private static final long CAT_SORT = 44102L;
    private static final long CAT_PAGE = 44103L;
    private static final long CAT_SOLDOUT = 44104L;
    private static final long CAT_DETAIL = 44105L;
    private static final List<Long> CATEGORY_IDS = List.of(CAT_EXCL, CAT_SORT, CAT_PAGE, CAT_SOLDOUT, CAT_DETAIL);

    // 노출 대상 상품(비교 기준) + 상세 대상 public_id
    private static final String PID_VISIBLE = prd("PVISIBLE");
    private static final String PID_HIDDEN = prd("PHIDDEN");
    private static final String PID_SUSP = prd("PSUSP");
    private static final String PID_MULTI = prd("PMULTI");
    private static final String PID_SIMPLE = prd("PSIMPLE");

    static final MariaDBContainer<?> MARIADB;

    static {
        MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;
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

    // ==================== 목록 노출/비노출(D1) ====================

    @Test
    @DisplayName("T1 목록 노출대상 필터 — SALE∧ACTIVE만 노출·HIDDEN/STOPPED/판매자SUSPENDED/TERMINATED/삭제 제외")
    void list_excludesNonDisplayable() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_EXCL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productPublicId").value(PID_VISIBLE))
                .andExpect(jsonPath("$.items[0].sellerName").value("액티브셀러"))
                .andExpect(jsonPath("$.items[0].categoryId").value(CAT_EXCL));
    }

    @Test
    @DisplayName("T2 카테고리 필터 — categoryId 미지정 시 전 카테고리, 지정 시 해당만")
    void list_filterByCategory() throws Exception {
        // CAT_SORT에는 3개만 노출(다른 카테고리 상품 미포함)
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3));
    }

    // ==================== 정렬(D4) ====================

    @Test
    @DisplayName("T3 LATEST 정렬 — created_at DESC(다>나>가)")
    void list_sortLatest() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SORT)).param("sort", "LATEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("다상품"))
                .andExpect(jsonPath("$.items[1].name").value("나상품"))
                .andExpect(jsonPath("$.items[2].name").value("가상품"));
    }

    @Test
    @DisplayName("T4 PRICE_ASC 정렬 — 대표가 오름차순(5000<15000<30000)")
    void list_sortPriceAsc() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SORT)).param("sort", "PRICE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].displayPrice").value(5000))
                .andExpect(jsonPath("$.items[1].displayPrice").value(15000))
                .andExpect(jsonPath("$.items[2].displayPrice").value(30000));
    }

    @Test
    @DisplayName("T5 PRICE_DESC 정렬 — 대표가 내림차순(30000>15000>5000)")
    void list_sortPriceDesc() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SORT)).param("sort", "PRICE_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].displayPrice").value(30000))
                .andExpect(jsonPath("$.items[1].displayPrice").value(15000))
                .andExpect(jsonPath("$.items[2].displayPrice").value(5000));
    }

    @Test
    @DisplayName("T6 NAME 정렬 — 상품명 오름차순(가<나<다)")
    void list_sortName() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SORT)).param("sort", "NAME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("가상품"))
                .andExpect(jsonPath("$.items[1].name").value("나상품"))
                .andExpect(jsonPath("$.items[2].name").value("다상품"));
    }

    // ==================== 페이징(D-54) ====================

    @Test
    @DisplayName("T7 페이징 — size=2 page0 → 2건·hasNext true·totalCount 3, page1 → 1건·hasNext false")
    void list_paging() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_PAGE)).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));

        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_PAGE)).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    // ==================== 품절(D2) ====================

    @Test
    @DisplayName("T8 품절 배지 — 가용재고 0 상품 soldOut true·재고 보유 상품 soldOut false")
    void list_soldOutBadge() throws Exception {
        // CAT_SOLDOUT: 품절 상품(available 0) + 재고 상품(available 5). PRICE_ASC로 순서 고정(둘 다 base 10000·품절 상품 먼저 등록).
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_SOLDOUT)).param("sort", "NAME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                // "가품절" < "나재고" (NAME asc)
                .andExpect(jsonPath("$.items[0].name").value("가품절"))
                .andExpect(jsonPath("$.items[0].soldOut").value(true))
                .andExpect(jsonPath("$.items[1].name").value("나재고"))
                .andExpect(jsonPath("$.items[1].soldOut").value(false));
    }

    // ==================== 단건 상세(D1~D3·sentinel) ====================

    @Test
    @DisplayName("T9 단건 상세 200 — 옵션 그룹/값·variant(salePrice·soldOut·옵션 조합)·대표가 MIN(additional)")
    void detail_returns200_withOptionsAndVariants() throws Exception {
        // P_MULTI: base 10000, 색상[검정(add500·재고5·order0)·빨강(add200·수동품절·order1)]. 대표가=10000+MIN(500,200)=10200.
        mockMvc.perform(get(URL + "/" + PID_MULTI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPublicId").value(PID_MULTI))
                .andExpect(jsonPath("$.name").value("멀티옵션상품"))
                .andExpect(jsonPath("$.sellerName").value("액티브셀러"))
                .andExpect(jsonPath("$.displayPrice").value(10200))
                .andExpect(jsonPath("$.soldOut").value(false))
                .andExpect(jsonPath("$.optionGroups.length()").value(1))
                .andExpect(jsonPath("$.optionGroups[0].name").value("색상"))
                .andExpect(jsonPath("$.optionGroups[0].values.length()").value(2))
                .andExpect(jsonPath("$.variants.length()").value(2))
                // display_order 0 = 검정(add500 → salePrice 10500·구매가능 soldOut false)
                .andExpect(jsonPath("$.variants[0].salePrice").value(10500))
                .andExpect(jsonPath("$.variants[0].soldOut").value(false))
                .andExpect(jsonPath("$.variants[0].options[0].groupName").value("색상"))
                .andExpect(jsonPath("$.variants[0].options[0].value").value("검정"))
                // display_order 1 = 빨강(add200 → salePrice 10200·수동품절 soldOut true)
                .andExpect(jsonPath("$.variants[1].salePrice").value(10200))
                .andExpect(jsonPath("$.variants[1].soldOut").value(true));
    }

    @Test
    @DisplayName("T10 DEFAULT sentinel 숨김 — 단순상품 상세는 optionGroups·variant options 빈 목록")
    void detail_hidesDefaultSentinel() throws Exception {
        mockMvc.perform(get(URL + "/" + PID_SIMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPublicId").value(PID_SIMPLE))
                .andExpect(jsonPath("$.optionGroups.length()").value(0))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].options.length()").value(0));
    }

    // ==================== 단건 404(은닉) ====================

    @Test
    @DisplayName("T11 단건 404 — 미존재 public_id → PRODUCT_NOT_FOUND")
    void detail_notFound_nonexistent() throws Exception {
        mockMvc.perform(get(URL + "/" + prd("NOEXIST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("T12 단건 404 — HIDDEN 상품은 존재해도 비노출로 은닉")
    void detail_notFound_hiddenProduct() throws Exception {
        mockMvc.perform(get(URL + "/" + PID_HIDDEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("T13 단건 404 — 판매자 SUSPENDED 상품은 비노출로 은닉")
    void detail_notFound_suspendedSeller() throws Exception {
        mockMvc.perform(get(URL + "/" + PID_SUSP))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    // ==================== 공개 접근·파라미터 검증 ====================

    @Test
    @DisplayName("T14 공개 접근 — 미인증(토큰 없음) GET 목록 200")
    void list_publicAccess_unauthenticated() throws Exception {
        mockMvc.perform(get(URL).param("categoryId", String.valueOf(CAT_EXCL)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("T15 잘못된 sort 값 → 400 MALFORMED_REQUEST")
    void list_invalidSort_returns400() throws Exception {
        mockMvc.perform(get(URL).param("sort", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ==================== seed·helpers ====================

    private record VariantSpec(
            String optionLabel, long additionalPrice, String status, boolean soldoutManual, int available, int displayOrder) {
    }

    private static VariantSpec v(String label, long add, String status, boolean manual, int available, int order) {
        return new VariantSpec(label, add, status, manual, available, order);
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seedFixtures() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSeller(SELLER_ACTIVE, "액티브셀러", "ACTIVE");
                seedSeller(SELLER_SUSPENDED, "정지셀러", "SUSPENDED");
                seedSeller(SELLER_TERMINATED, "해지셀러", "TERMINATED");
                for (Long categoryId : CATEGORY_IDS) {
                    seedCategory(categoryId);
                }

                LocalDateTime base = LocalDateTime.of(2026, 7, 1, 0, 0);

                // CAT_EXCL — 노출 1건 + 비노출 5건
                seedProduct(44201L, "PVISIBLE", SELLER_ACTIVE, CAT_EXCL, "노출상품", "SALE", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));
                seedProduct(44202L, "PHIDDEN", SELLER_ACTIVE, CAT_EXCL, "숨김상품", "HIDDEN", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));
                seedProduct(44203L, "PSTOPPED", SELLER_ACTIVE, CAT_EXCL, "중지상품", "STOPPED", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));
                seedProduct(44204L, "PSUSP", SELLER_SUSPENDED, CAT_EXCL, "정지셀러상품", "SALE", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));
                seedProduct(44205L, "PTERM", SELLER_TERMINATED, CAT_EXCL, "해지셀러상품", "SALE", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));
                seedProduct(44206L, "PDELETED", SELLER_ACTIVE, CAT_EXCL, "삭제상품", "SALE", 10000L, base, base,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));

                // CAT_SORT — 대표가 5000/15000/30000·이름 가/나/다·created_at 가<나<다
                seedProduct(44211L, "PSCHEAP", SELLER_ACTIVE, CAT_SORT, "가상품", "SALE", 5000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));
                seedProduct(44212L, "PSMID", SELLER_ACTIVE, CAT_SORT, "나상품", "SALE", 10000L, base.plusDays(1), null,
                        "색상", List.of(v("검정", 5000, "SALE", false, 5, 0)));
                seedProduct(44213L, "PSEXP", SELLER_ACTIVE, CAT_SORT, "다상품", "SALE", 30000L, base.plusDays(2), null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));

                // CAT_PAGE — 3건
                seedProduct(44221L, "PPAGE1", SELLER_ACTIVE, CAT_PAGE, "페이지1", "SALE", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));
                seedProduct(44222L, "PPAGE2", SELLER_ACTIVE, CAT_PAGE, "페이지2", "SALE", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));
                seedProduct(44223L, "PPAGE3", SELLER_ACTIVE, CAT_PAGE, "페이지3", "SALE", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));

                // CAT_SOLDOUT — 품절(available 0) + 재고
                seedProduct(44231L, "PSOLDOUT", SELLER_ACTIVE, CAT_SOLDOUT, "가품절", "SALE", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 0, 0)));
                seedProduct(44232L, "PINSTOCK", SELLER_ACTIVE, CAT_SOLDOUT, "나재고", "SALE", 10000L, base, null,
                        "색상", List.of(v("검정", 0, "SALE", false, 5, 0)));

                // CAT_DETAIL — 멀티옵션 + 단순(DEFAULT)
                seedProduct(44241L, "PMULTI", SELLER_ACTIVE, CAT_DETAIL, "멀티옵션상품", "SALE", 10000L, base, null,
                        "색상", List.of(
                                v("검정", 500, "SALE", false, 5, 0),
                                v("빨강", 200, "SALE", true, 10, 1)));
                seedProduct(44242L, "PSIMPLE", SELLER_ACTIVE, CAT_DETAIL, "단순상품", "SALE", 8000L, base, null,
                        "DEFAULT", List.of(v("DEFAULT", 0, "SALE", false, 3, 0)));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSeller(long id, String companyName, String status) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))",
                id, pad("slr_", "CATSLR" + id), companyName, status);
    }

    private void seedCategory(long id) {
        jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, ?, 0, 0, NOW(6), NOW(6))",
                id, "카테고리" + id);
    }

    private void seedProduct(long productId, String tag, long sellerId, long categoryId, String name, String status,
            long basePrice, LocalDateTime createdAt, LocalDateTime deletedAt, String groupName, List<VariantSpec> variants) {
        Timestamp created = Timestamp.valueOf(createdAt);
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, description, status, base_price, "
                        + "thumbnail_url, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)",
                productId, prd(tag), sellerId, categoryId, name, status, basePrice,
                "https://img/" + tag, created, created, deletedAt == null ? null : Timestamp.valueOf(deletedAt));

        long groupId = productId * 10;
        jdbc.update("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, NOW(6), NOW(6))",
                groupId, productId, groupName);

        int idx = 0;
        for (VariantSpec spec : variants) {
            long valueId = productId * 10 + idx + 1;
            jdbc.update("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                    valueId, groupId, spec.optionLabel(), idx);

            long variantId = productId * 100 + idx;
            jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                            + "is_soldout_manual, display_order, option1_value_id, option2_value_id, option3_value_id, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NOW(6), NOW(6))",
                    variantId, pad("var_", tag + idx), productId, "SKU-" + tag + idx, spec.additionalPrice(),
                    spec.status(), spec.soldoutManual() ? 1 : 0, spec.displayOrder(), valueId);

            jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                            + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))",
                    variantId, variantId, spec.available(), spec.available());
            idx++;
        }
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                String sellerIn = "(" + join(SELLER_IDS) + ")";
                jdbc.update("DELETE FROM inventory WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id IN "
                        + "(SELECT id FROM product WHERE seller_id IN " + sellerIn + "))");
                jdbc.update("DELETE FROM product_variant WHERE product_id IN (SELECT id FROM product WHERE seller_id IN "
                        + sellerIn + ")");
                jdbc.update("DELETE FROM product_option_value WHERE option_group_id IN (SELECT id FROM product_option_group "
                        + "WHERE product_id IN (SELECT id FROM product WHERE seller_id IN " + sellerIn + "))");
                jdbc.update("DELETE FROM product_option_group WHERE product_id IN (SELECT id FROM product WHERE seller_id IN "
                        + sellerIn + ")");
                jdbc.update("DELETE FROM product WHERE seller_id IN " + sellerIn);
                jdbc.update("DELETE FROM seller WHERE id IN " + sellerIn);
                jdbc.update("DELETE FROM category WHERE id IN (" + join(CATEGORY_IDS) + ")");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    // SELLER_IDS·CATEGORY_IDS는 코드 상수(정적 long 리터럴)만 조인한다(외부 입력 없음·SQL injection 위험 없음).
    private static String join(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static String prd(String tag) {
        return pad("prd_", tag);
    }

    private static String pad(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

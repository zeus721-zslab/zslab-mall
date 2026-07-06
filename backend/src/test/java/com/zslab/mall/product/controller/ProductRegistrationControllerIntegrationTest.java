package com.zslab.mall.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.product.controller.request.ProductOptionGroupRequest;
import com.zslab.mall.product.controller.request.ProductOptionValueRequest;
import com.zslab.mall.product.controller.request.ProductRegistrationRequest;
import com.zslab.mall.product.controller.request.ProductVariantRequest;
import com.zslab.mall.product.controller.response.ProductRegistrationResponse;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 상품 등록 endpoint E2E 통합 테스트(Track 39 P6·실 MariaDB). HTTP → {@code ProductRegistrationController} →
 * {@code ProductRegistrationService} → Product·OptionGroup·OptionValue·ProductVariant·Inventory·InventoryHistory
 * 그래프 원자 생성 흐름을 실 커밋·HTTP 경유로 실측한다({@code SellerInventoryControllerIntegrationTest} 픽스처 패턴 정합).
 * P1~P5(팩토리·DTO·오케스트레이션·Controller·예외 매핑)와 보강분(단순상품 DEFAULT·INV-E·R5-3) 전량을 회귀 잠금한다.
 *
 * <p><b>seed</b>: seller_user(user_id→seller_id·role SELLER_OWNER)로 {@code SellerActorResolver}를 해소하고, categoryId
 * 검증용 category 1행을 실 seed한다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02)로 한다.
 * 생성 그래프는 IDENTITY id라 seller_id 체인으로 정리한다. 등록 트랜잭션 커밋은 JdbcTemplate 직접 조회로 검증하므로
 * 클래스에 {@code @Transactional}을 두지 않는다.
 *
 * <p><b>409 재현성</b>: 등록은 매 요청 새 product_id를 만들고 uk_product_variant_options는 product_id 스코프이므로, 요청 내
 * 동일 옵션 조합은 INV-E(in-memory 400)가 saveVariants 진입 전에 선차단한다. uk 409(saveVariants catch)는 3그룹 non-NULL
 * 보조 방어선으로 남지만 엔드포인트 경로로는 재현되지 않는다(T11에서 3그룹 동일조합이 INV-E 400으로 처리됨을 실증).
 */
@AutoConfigureMockMvc
class ProductRegistrationControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/seller/products";

    private static final long SELLER_ID = 9540L;
    private static final long SELLER_USER_ID = 9541L; // JWT subject(actorId)·seller_user 매핑 소스
    private static final long BUYER_USER_ID = 9542L;   // 비-SELLER role 403 검증용
    private static final long CATEGORY_ID = 9543L;
    private static final long MISSING_CATEGORY_ID = 9599L; // 미seed·404 검증용

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

    // ==================== 정상계열(STEP 6-2) ====================

    @Test
    @DisplayName("T1 옵션상품 정상 등록(1그룹 색상·검정/붉은색 2변형) → 201 + Product/Group/Value/Variant/Inventory/History 생성")
    void register_optionProduct_returns201_persistsGraph() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(group("색상", 0, value("k_black", "검정", 0), value("k_red", "붉은색", 1))),
                List.of(
                        variant("TS-BLACK", 0, 0, 10, "k_black"),
                        variant("TS-RED", 500, 1, 5, "k_red")));

        MvcResult result = register(sellerAuth(), request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productPublicId").exists())
                .andExpect(jsonPath("$.variantPublicIds.length()").value(2))
                .andReturn();

        ProductRegistrationResponse response = extract(result);
        assertThat(response.productPublicId()).startsWith("prd_");
        assertThat(response.variantPublicIds()).hasSize(2).allSatisfy(id -> assertThat(id).startsWith("var_"));

        Long productId = productId(response.productPublicId());
        // Product 본문(status PENDING·seller/category·base_price 서버 결정·Track 50 등록 초기 PENDING·승인 시 SALE 전이)
        assertThat(count("SELECT COUNT(*) FROM product WHERE id=? AND seller_id=? AND category_id=? AND status='PENDING' AND base_price=10000",
                productId, SELLER_ID, CATEGORY_ID)).isEqualTo(1);
        // 옵션 그래프
        assertThat(count("SELECT COUNT(*) FROM product_option_group WHERE product_id=? AND name='색상'", productId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product_option_value WHERE option_group_id IN "
                + "(SELECT id FROM product_option_group WHERE product_id=?)", productId)).isEqualTo(2);
        // Variant(status SALE·is_soldout_manual 0·option1 채움·option2/3 NULL·1~2그룹)
        assertThat(count("SELECT COUNT(*) FROM product_variant WHERE product_id=? AND status='SALE' AND is_soldout_manual=0 "
                + "AND option1_value_id IS NOT NULL AND option2_value_id IS NULL AND option3_value_id IS NULL", productId)).isEqualTo(2);
        // Inventory(on_hand=요청·reserved=0·available=on_hand) — 변형별
        assertVariantInventory(productId, "TS-BLACK", 10);
        assertVariantInventory(productId, "TS-RED", 5);
        // History(INBOUND·delta=initialStock·reference_type 'product'·reference_id=productId) 2행
        assertThat(count("SELECT COUNT(*) FROM inventory_history ih JOIN inventory i ON ih.inventory_id=i.id "
                + "JOIN product_variant pv ON i.variant_id=pv.id WHERE pv.product_id=? AND ih.change_type='INBOUND' "
                + "AND ih.reference_type='product' AND ih.reference_id=?", productId, productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("T2 단순상품 정상 등록(optionGroups=[]·변형1·optionKeys=[]) → 201 + DEFAULT 옵션 자동생성·변형1·재고/이력")
    void register_simpleProduct_returns201_synthesizesDefaultOption() throws Exception {
        ProductRegistrationRequest request = product(List.of(), List.of(variant("SIMPLE-1", 0, 0, 7)));

        MvcResult result = register(sellerAuth(), request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variantPublicIds.length()").value(1))
                .andReturn();

        Long productId = productId(extract(result).productPublicId());
        // DEFAULT 옵션 그룹·값 자동 생성(sentinel)
        assertThat(count("SELECT COUNT(*) FROM product_option_group WHERE product_id=? AND name='DEFAULT'", productId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product_option_value WHERE value='DEFAULT' AND option_group_id IN "
                + "(SELECT id FROM product_option_group WHERE product_id=?)", productId)).isEqualTo(1);
        // 변형 1개·option1=DEFAULT valueId·option2/3 NULL
        assertThat(count("SELECT COUNT(*) FROM product_variant WHERE product_id=? AND status='SALE' "
                + "AND option1_value_id IS NOT NULL AND option2_value_id IS NULL", productId)).isEqualTo(1);
        assertVariantInventory(productId, "SIMPLE-1", 7);
        assertThat(count("SELECT COUNT(*) FROM inventory_history ih JOIN inventory i ON ih.inventory_id=i.id "
                + "JOIN product_variant pv ON i.variant_id=pv.id WHERE pv.product_id=? AND ih.change_type='INBOUND'", productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("T13 초기재고 0 등록 → 201 + Inventory available=0·History 1행(delta=0 baseline)")
    void register_zeroInitialStock_returns201_inventoryZero() throws Exception {
        ProductRegistrationRequest request = product(List.of(), List.of(variant("ZERO-1", 0, 0, 0)));

        MvcResult result = register(sellerAuth(), request).andExpect(status().isCreated()).andReturn();
        Long productId = productId(extract(result).productPublicId());

        assertVariantInventory(productId, "ZERO-1", 0);
        // initialStock=0도 감사 baseline으로 History 1행(delta=0) 기록
        assertThat(count("SELECT COUNT(*) FROM inventory_history ih JOIN inventory i ON ih.inventory_id=i.id "
                + "JOIN product_variant pv ON i.variant_id=pv.id WHERE pv.product_id=? AND ih.change_type='INBOUND' "
                + "AND ih.quantity_delta=0", productId)).isEqualTo(1);
    }

    // ==================== 검증위반 400(STEP 6-3) ====================

    @Test
    @DisplayName("T3 단순상품인데 변형 2개(optionGroups=[]·variants 2) → 400 MALFORMED_REQUEST")
    void register_simpleButTwoVariants_returns400() throws Exception {
        ProductRegistrationRequest request = product(List.of(),
                List.of(variant("A", 0, 0, 1), variant("B", 0, 1, 1)));
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T4 INV-D 옵션 그룹 4개 → 400 MALFORMED_REQUEST")
    void register_invD_fourGroups_returns400() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(
                        group("G1", 0, value("k1", "v1", 0)),
                        group("G2", 1, value("k2", "v2", 0)),
                        group("G3", 2, value("k3", "v3", 0)),
                        group("G4", 3, value("k4", "v4", 0))),
                List.of(variant("V", 0, 0, 1, "k1", "k2", "k3", "k4")));
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T5 INV-B arity 불일치(그룹 2·변형 optionKeys 1) → 400 MALFORMED_REQUEST")
    void register_invB_arityMismatch_returns400() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(group("G1", 0, value("k1", "v1", 0)), group("G2", 1, value("k2", "v2", 0))),
                List.of(variant("V", 0, 0, 1, "k1"))); // 그룹 2개인데 optionKeys 1개
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T6 INV-A 한 그룹에서 2값 참조(arity는 일치·2그룹) → 400 MALFORMED_REQUEST")
    void register_invA_twoValuesSameGroup_returns400() throws Exception {
        // 2그룹으로 arity(INV-B)를 통과시키고, 변형이 색상 그룹에서만 2값을 참조해 INV-A(그룹당 1값)를 위반한다.
        ProductRegistrationRequest request = product(
                List.of(
                        group("색상", 0, value("k1a", "검정", 0), value("k1b", "붉은색", 1)),
                        group("사이즈", 1, value("k2", "L", 0))),
                List.of(variant("V", 0, 0, 1, "k1a", "k1b"))); // 둘 다 색상 그룹 → INV-A 위반(사이즈 미참조)
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T7 INV-C 미존재 임시키 참조 → 400 MALFORMED_REQUEST")
    void register_invC_unknownTempKey_returns400() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(group("색상", 0, value("k_black", "검정", 0))),
                List.of(variant("V", 0, 0, 1, "k_missing"))); // 정의되지 않은 키
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T8 임시키 중복 정의 → 400 MALFORMED_REQUEST")
    void register_duplicateTempKey_returns400() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(group("색상", 0, value("dup", "검정", 0), value("dup", "붉은색", 1))), // 같은 key 재정의
                List.of(variant("V", 0, 0, 1, "dup")));
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T9 INV-E 동일 옵션값 조합 2변형(1그룹 1값) → 400 + Product·Variant 전량 롤백(원자성)")
    void register_invE_duplicateCombination_returns400_rollsBack() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(group("색상", 0, value("k_black", "검정", 0))),
                List.of(variant("V1", 0, 0, 1, "k_black"), variant("V2", 0, 1, 1, "k_black"))); // 동일 조합
        expectBadRequest(request);

        // INV-E는 Product·OptionGroup·OptionValue 저장 후 saveVariants 진입 전에 throw → @Transactional 전량 롤백.
        assertThat(count("SELECT COUNT(*) FROM product WHERE seller_id=?", SELLER_ID)).isZero();
        assertThat(count("SELECT COUNT(*) FROM product_option_group")).isZero();
        assertThat(count("SELECT COUNT(*) FROM product_variant")).isZero();
    }

    @Test
    @DisplayName("T10a R5-3 그룹 간 displayOrder 중복 → 400 MALFORMED_REQUEST")
    void register_r53_duplicateGroupDisplayOrder_returns400() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(group("G1", 0, value("k1", "v1", 0)), group("G2", 0, value("k2", "v2", 0))), // 그룹 order 0 중복
                List.of(variant("V", 0, 0, 1, "k1", "k2")));
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T10b R5-3 그룹 내 값 간 displayOrder 중복 → 400 MALFORMED_REQUEST")
    void register_r53_duplicateValueDisplayOrder_returns400() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(group("색상", 0, value("k_black", "검정", 0), value("k_red", "붉은색", 0))), // 값 order 0 중복
                List.of(variant("V", 0, 0, 1, "k_black")));
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T10c R5-3 변형 간 displayOrder 중복 → 400 MALFORMED_REQUEST")
    void register_r53_duplicateVariantDisplayOrder_returns400() throws Exception {
        ProductRegistrationRequest request = product(
                List.of(group("색상", 0, value("k_black", "검정", 0), value("k_red", "붉은색", 1))),
                List.of(variant("V1", 0, 0, 1, "k_black"), variant("V2", 0, 0, 1, "k_red"))); // 변형 order 0 중복
        expectBadRequest(request);
    }

    // ==================== 상태코드(STEP 6-4) ====================

    @Test
    @DisplayName("T11 INV-E 3그룹 동일조합(non-NULL) → 400(uk 409 대체·요청 내 중복은 INV-E 선차단)")
    void register_invE_threeGroupsDuplicate_returns400() throws Exception {
        // 3그룹 각 1값 → 두 변형이 (v1,v2,v3) 동일 조합(전부 non-NULL). uk가 적용될 3그룹 경로라도 요청 내 중복은 INV-E 400.
        ProductRegistrationRequest request = product(
                List.of(
                        group("색상", 0, value("k1", "검정", 0)),
                        group("사이즈", 1, value("k2", "L", 0)),
                        group("소재", 2, value("k3", "면", 0))),
                List.of(variant("V1", 0, 0, 1, "k1", "k2", "k3"), variant("V2", 0, 1, 1, "k1", "k2", "k3")));
        expectBadRequest(request);
    }

    @Test
    @DisplayName("T12 categoryId 미존재 → 404 CATEGORY_NOT_FOUND")
    void register_unknownCategory_returns404() throws Exception {
        ProductRegistrationRequest request = req(MISSING_CATEGORY_ID, List.of(), List.of(variant("V", 0, 0, 1)));
        register(sellerAuth(), request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("T14 미인증(토큰 없음) → 401 UNAUTHENTICATED")
    void register_unauthenticated_returns401() throws Exception {
        ProductRegistrationRequest request = product(List.of(), List.of(variant("V", 0, 0, 1)));
        register(new HttpHeaders(), request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("T15 비-SELLER role(BUYER 토큰) → 403 FORBIDDEN")
    void register_buyerRole_returns403() throws Exception {
        ProductRegistrationRequest request = product(List.of(), List.of(variant("V", 0, 0, 1)));
        register(authHeaders.buyer(BUYER_USER_ID), request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ==================== seed·helpers ====================

    private HttpHeaders sellerAuth() {
        return authHeaders.seller(SELLER_USER_ID);
    }

    private ResultActions register(HttpHeaders headers, ProductRegistrationRequest request) throws Exception {
        return mockMvc.perform(post(URL)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    /** 도메인 검증 위반(INV-A~E·R5-3·단순상품)은 IllegalArgumentException→400 MALFORMED_REQUEST로 매핑됨을 단언한다. */
    private void expectBadRequest(ProductRegistrationRequest request) throws Exception {
        register(sellerAuth(), request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    private ProductRegistrationResponse extract(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), ProductRegistrationResponse.class);
    }

    private static ProductOptionValueRequest value(String key, String value, int displayOrder) {
        return new ProductOptionValueRequest(key, value, displayOrder);
    }

    private static ProductOptionGroupRequest group(String name, int displayOrder, ProductOptionValueRequest... values) {
        return new ProductOptionGroupRequest(name, displayOrder, List.of(values));
    }

    private static ProductVariantRequest variant(
            String variantCode, long additionalPrice, int displayOrder, int initialStock, String... optionKeys) {
        return new ProductVariantRequest(variantCode, null, null, additionalPrice, displayOrder, initialStock, List.of(optionKeys));
    }

    private static ProductRegistrationRequest req(
            long categoryId, List<ProductOptionGroupRequest> groups, List<ProductVariantRequest> variants) {
        return new ProductRegistrationRequest(categoryId, "테스트상품", null, 10000L, null, groups, variants);
    }

    private ProductRegistrationRequest product(List<ProductOptionGroupRequest> groups, List<ProductVariantRequest> variants) {
        return req(CATEGORY_ID, groups, variants);
    }

    private Long productId(String publicId) {
        return jdbc.queryForObject("SELECT id FROM product WHERE public_id=?", Long.class, publicId);
    }

    private void assertVariantInventory(Long productId, String variantCode, int expectedOnHand) {
        Integer onHand = jdbc.queryForObject("SELECT i.quantity_on_hand FROM inventory i "
                + "JOIN product_variant pv ON i.variant_id=pv.id WHERE pv.product_id=? AND pv.variant_code=?",
                Integer.class, productId, variantCode);
        Integer reserved = jdbc.queryForObject("SELECT i.quantity_reserved FROM inventory i "
                + "JOIN product_variant pv ON i.variant_id=pv.id WHERE pv.product_id=? AND pv.variant_code=?",
                Integer.class, productId, variantCode);
        Integer available = jdbc.queryForObject("SELECT i.quantity_available FROM inventory i "
                + "JOIN product_variant pv ON i.variant_id=pv.id WHERE pv.product_id=? AND pv.variant_code=?",
                Integer.class, productId, variantCode);
        assertThat(onHand).isEqualTo(expectedOnHand);
        assertThat(reserved).isZero();
        assertThat(available).isEqualTo(expectedOnHand);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    private void seedFixtures() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        SELLER_USER_ID, pid("usr_", "PRGUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '등록테스트셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "PRGSLR"));
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        SELLER_USER_ID, SELLER_ID);
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                                + "VALUES (?, '테스트카테고리', 0, 0, NOW(6), NOW(6))",
                        CATEGORY_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    // 생성 그래프는 IDENTITY id라 seller_id 체인으로 정리한다(자식→부모 순서·서브쿼리 해소 위해 product는 마지막 삭제).
    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
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
                jdbc.update("DELETE FROM category WHERE id=?", CATEGORY_ID);
                jdbc.update("DELETE FROM `user` WHERE id=?", SELLER_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

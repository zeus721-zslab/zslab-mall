package com.zslab.mall.cart.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.cart.controller.response.CartItemView;
import com.zslab.mall.cart.controller.response.CartResponse;
import com.zslab.mall.common.security.AuthHeaders;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 장바구니 관리(조회/삭제/수량변경/selected 토글) endpoint E2E 통합 테스트(Track 45·실 MariaDB·MockMvc). HTTP →
 * {@code CartController} → {@code CartService} → cart_item·enrich 흐름을 실 커밋·HTTP 경유로 실측한다
 * ({@code CartControllerIntegrationTest} 픽스처 패턴 정합).
 *
 * <p><b>seed</b>: buyer user·판매자·상품·변형 4종(정상·품절·수동품절·dangling)·재고를 실 seed한다(enrich는 @SQLRestriction
 * JPA 조회라 실 행 필요). dangling은 variant deleted_at 마킹으로 findByIdIn에서 누락시킨다. 커밋은 JdbcTemplate 직접 조회로
 * 검증하므로 클래스에 {@code @Transactional}을 두지 않는다. 시드/정리는 {@code FOREIGN_KEY_CHECKS=0}(LT-02).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CartManagementControllerIntegrationTest {

    private static final long BUYER_USER_ID = 45640L;
    private static final long OTHER_BUYER_ID = 45641L;   // 소유권 격리 검증
    private static final long SELLER_ACTOR_ID = 45642L;  // 비-BUYER 403 검증

    private static final long SELLER_ID = 45001L;
    private static final long PRODUCT_ID = 45201L;
    private static final long BASE_PRICE = 10000L;

    private static final long VAR_OK = 45301L;        // SALE·재고5·additional500 → 구매가능·displayPrice 10500
    private static final long VAR_SOLDOUT = 45302L;   // SALE·재고0 → 품절
    private static final long VAR_MANUAL = 45303L;    // SALE·재고5·수동품절 → 품절
    private static final long VAR_DANGLING = 45304L;  // deleted_at 마킹 → enrich 누락·purchasable false

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

    // ==================== 조회 enrich ====================

    @Test
    @DisplayName("T1 조회 enrich — 정상/품절/수동품절/dangling 4항목: productName·displayPrice·품절·purchasable 플래그")
    void getCart_enrich() throws Exception {
        seedCartItem(BUYER_USER_ID, VAR_OK, 2, true);
        seedCartItem(BUYER_USER_ID, VAR_SOLDOUT, 1, true);
        seedCartItem(BUYER_USER_ID, VAR_MANUAL, 1, true);
        seedCartItem(BUYER_USER_ID, VAR_DANGLING, 1, true);

        MvcResult result = mockMvc.perform(get("/api/v1/cart").headers(authHeaders.buyer(BUYER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andReturn();

        CartResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), CartResponse.class);

        CartItemView ok = view(response, VAR_OK);
        assertThat(ok.productName()).isEqualTo("카트상품");
        assertThat(ok.sellerName()).isEqualTo("카트셀러");
        assertThat(ok.displayPrice()).isEqualTo(10500L); // base 10000 + additional 500
        assertThat(ok.quantityAvailable()).isEqualTo(5);
        assertThat(ok.purchasable()).isTrue();
        assertThat(ok.quantity()).isEqualTo(2);

        assertThat(view(response, VAR_SOLDOUT).purchasable()).isFalse();   // 재고0
        assertThat(view(response, VAR_MANUAL).purchasable()).isFalse();    // 수동품절

        // dangling: enrich 누락 → 표기 유지·구매불가
        CartItemView dangling = view(response, VAR_DANGLING);
        assertThat(dangling.purchasable()).isFalse();
        assertThat(dangling.productName()).isNull();
        assertThat(dangling.quantityAvailable()).isZero();
        assertThat(dangling.displayPrice()).isZero();
    }

    @Test
    @DisplayName("T2 빈 장바구니 조회 → 200 items 빈 목록")
    void getCart_empty() throws Exception {
        mockMvc.perform(get("/api/v1/cart").headers(authHeaders.buyer(BUYER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ==================== 삭제 ====================

    @Test
    @DisplayName("T3 단건 삭제 — variantIds=[VAR_OK] → 200·VAR_OK 제거·타 항목 잔존")
    void delete_single() throws Exception {
        seedCartItem(BUYER_USER_ID, VAR_OK, 1, true);
        seedCartItem(BUYER_USER_ID, VAR_SOLDOUT, 1, true);

        mockMvc.perform(delete("/api/v1/cart/items").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantIds", List.of(VAR_OK)))))
                .andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=? AND variant_id=?", BUYER_USER_ID, VAR_OK)).isZero();
        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=? AND variant_id=?", BUYER_USER_ID, VAR_SOLDOUT)).isEqualTo(1);
    }

    @Test
    @DisplayName("T4 다건 삭제 — variantIds=[VAR_OK, VAR_SOLDOUT] → 200·둘 다 제거")
    void delete_multi() throws Exception {
        seedCartItem(BUYER_USER_ID, VAR_OK, 1, true);
        seedCartItem(BUYER_USER_ID, VAR_SOLDOUT, 1, true);

        mockMvc.perform(delete("/api/v1/cart/items").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantIds", List.of(VAR_OK, VAR_SOLDOUT)))))
                .andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=?", BUYER_USER_ID)).isZero();
    }

    @Test
    @DisplayName("T5 삭제 빈 배열 → 400 VALIDATION_FAILED(@NotEmpty)")
    void delete_emptyList_returns400() throws Exception {
        mockMvc.perform(delete("/api/v1/cart/items").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantIds", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ==================== 수량변경 ====================

    @Test
    @DisplayName("T6 수량변경 절대값 — quantity=9 → 200·cart_item quantity=9(누적 아님)")
    void changeQuantity_absolute() throws Exception {
        seedCartItem(BUYER_USER_ID, VAR_OK, 2, true);

        mockMvc.perform(patch("/api/v1/cart/items/quantity").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantId", VAR_OK, "quantity", 9))))
                .andExpect(status().isOk());

        assertThat(count("SELECT quantity FROM cart_item WHERE user_id=? AND variant_id=?", BUYER_USER_ID, VAR_OK)).isEqualTo(9);
    }

    @Test
    @DisplayName("T7 수량변경 quantity<1(0) → 400 VALIDATION_FAILED(@Min(1))")
    void changeQuantity_belowOne_returns400() throws Exception {
        seedCartItem(BUYER_USER_ID, VAR_OK, 2, true);

        mockMvc.perform(patch("/api/v1/cart/items/quantity").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantId", VAR_OK, "quantity", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(count("SELECT quantity FROM cart_item WHERE user_id=? AND variant_id=?", BUYER_USER_ID, VAR_OK)).isEqualTo(2);
    }

    @Test
    @DisplayName("T8 수량변경 대상 미담김 → 404 CART_ITEM_NOT_FOUND")
    void changeQuantity_notInCart_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/cart/items/quantity").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantId", VAR_OK, "quantity", 3))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
    }

    // ==================== selected 토글 ====================

    @Test
    @DisplayName("T9 selected 단건 토글 — selected=false → 200·해당 항목만 해제")
    void setSelected_single() throws Exception {
        seedCartItem(BUYER_USER_ID, VAR_OK, 1, true);
        seedCartItem(BUYER_USER_ID, VAR_SOLDOUT, 1, true);

        mockMvc.perform(patch("/api/v1/cart/items/selected").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantId", VAR_OK, "selected", false))))
                .andExpect(status().isOk());

        assertThat(count("SELECT selected FROM cart_item WHERE user_id=? AND variant_id=?", BUYER_USER_ID, VAR_OK)).isZero();
        assertThat(count("SELECT selected FROM cart_item WHERE user_id=? AND variant_id=?", BUYER_USER_ID, VAR_SOLDOUT)).isEqualTo(1);
    }

    @Test
    @DisplayName("T10 selected 단건 대상 미담김 → 404 CART_ITEM_NOT_FOUND")
    void setSelected_notInCart_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/cart/items/selected").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantId", VAR_OK, "selected", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
    }

    @Test
    @DisplayName("T11 selected 전체 토글 — selected=false → 200·전 품목 해제")
    void setSelectedAll() throws Exception {
        seedCartItem(BUYER_USER_ID, VAR_OK, 1, true);
        seedCartItem(BUYER_USER_ID, VAR_SOLDOUT, 1, true);

        mockMvc.perform(patch("/api/v1/cart/items/selected/all").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("selected", false))))
                .andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=? AND selected=1", BUYER_USER_ID)).isZero();
        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=?", BUYER_USER_ID)).isEqualTo(2);
    }

    // ==================== 소유권 격리 ====================

    @Test
    @DisplayName("T12 소유권 격리 — 타 buyer 항목은 삭제·변경 불가(DELETE 무영향·PATCH 404)")
    void ownershipIsolation() throws Exception {
        seedCartItem(OTHER_BUYER_ID, VAR_OK, 3, true);

        // BUYER가 VAR_OK 삭제 시도 → 타인 항목 잔존
        mockMvc.perform(delete("/api/v1/cart/items").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantIds", List.of(VAR_OK)))))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM cart_item WHERE user_id=? AND variant_id=?", OTHER_BUYER_ID, VAR_OK)).isEqualTo(1);

        // BUYER가 VAR_OK 수량변경 시도 → 본인 장바구니엔 없음 → 404
        mockMvc.perform(patch("/api/v1/cart/items/quantity").headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("variantId", VAR_OK, "quantity", 1))))
                .andExpect(status().isNotFound());
        assertThat(count("SELECT quantity FROM cart_item WHERE user_id=? AND variant_id=?", OTHER_BUYER_ID, VAR_OK)).isEqualTo(3);
    }

    // ==================== 인가 ====================

    @Test
    @DisplayName("T13 미인증(토큰 없음) 조회 → 401 UNAUTHENTICATED")
    void getCart_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("T14 비-BUYER role(SELLER 토큰) 조회 → 403 FORBIDDEN")
    void getCart_sellerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/cart").headers(authHeaders.seller(SELLER_ACTOR_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ==================== helpers ====================

    private static CartItemView view(CartResponse response, long variantId) {
        return response.items().stream()
                .filter(item -> item.variantId() == variantId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("variantId=" + variantId + " 항목 없음"));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
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
                        BUYER_USER_ID, pid("usr_", "CM45B1"));
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        OTHER_BUYER_ID, pid("usr_", "CM45B2"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '카트셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "CM45SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "thumbnail_url, created_at, updated_at) VALUES (?, ?, ?, 1, '카트상품', 'SALE', ?, ?, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "CM45PRD"), SELLER_ID, BASE_PRICE, "https://img/cart");

                // 변형 4종(option1_value_id는 cart FK 대상 아님·FK_CHECKS=0으로 임의 1). dangling은 deleted_at 마킹.
                seedVariant(VAR_OK, "CM45V1", 500, "SALE", 0, null);
                seedVariant(VAR_SOLDOUT, "CM45V2", 0, "SALE", 0, null);
                seedVariant(VAR_MANUAL, "CM45V3", 0, "SALE", 1, null);
                seedVariant(VAR_DANGLING, "CM45V4", 0, "SALE", 0, "NOW(6)");

                seedInventory(VAR_OK, 5);
                seedInventory(VAR_SOLDOUT, 0);
                seedInventory(VAR_MANUAL, 5);
                seedInventory(VAR_DANGLING, 5);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedVariant(long id, String tag, long additionalPrice, String status, int soldoutManual, String deletedAtExpr) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, NOW(6), NOW(6), " + (deletedAtExpr == null ? "NULL" : deletedAtExpr) + ")",
                id, pid("var_", tag), PRODUCT_ID, "SKU-" + tag, additionalPrice, status, soldoutManual);
    }

    private void seedInventory(long variantId, int available) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))",
                variantId, variantId, available, available);
    }

    private void seedCartItem(long userId, long variantId, int quantity, boolean selected) {
        tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO cart_item (user_id, variant_id, quantity, selected, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                userId, variantId, quantity, selected ? 1 : 0));
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM cart_item WHERE user_id IN (?, ?)", BUYER_USER_ID, OTHER_BUYER_ID);
                jdbc.update("DELETE FROM inventory WHERE variant_id IN (?, ?, ?, ?)",
                        VAR_OK, VAR_SOLDOUT, VAR_MANUAL, VAR_DANGLING);
                jdbc.update("DELETE FROM product_variant WHERE product_id=?", PRODUCT_ID);
                jdbc.update("DELETE FROM product WHERE id=?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id=?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", BUYER_USER_ID, OTHER_BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

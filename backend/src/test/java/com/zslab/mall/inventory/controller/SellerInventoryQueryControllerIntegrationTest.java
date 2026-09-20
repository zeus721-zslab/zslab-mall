package com.zslab.mall.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.product.controller.response.AdminProductDetailResponse;
import com.zslab.mall.product.controller.response.AdminProductSummaryResponse;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 재고 목록 통합 테스트(Track 90-C-1·실 MariaDB·HTTP 경유). 행 축은 variant.
 *
 * <p><b>시드 그래프</b>: 셀러 A(user A·상태 파라미터)·셀러 B(user B·ACTIVE) /
 * 셀러 A 상품 — PA1("재고상품 알파"·옵션 그룹 색상{검정·빨강}·VA1(검정·SKU-BLK·10/2/8)·VA2(빨강·SKU-RED·0/0/0)·VA3(soft-delete)) ·
 * PA2("재고상품 베타"·단순상품 DEFAULT·VB1(SKU-BETA·5/0/5)) · PA3(soft-delete·VD1) / 셀러 B 상품 — PB("타셀러재고"·VPB(SKU-B)).
 * 기대: 셀러 A 목록 = VB1·VA1·VA2(상품 최신 id 우선 → variant displayOrder) 3행 — VA3·VD1(삭제)·VPB(타 셀러) 제외.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerInventoryQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String LIST_URL = "/api/v1/seller/inventories";

    private static final long USER_A = 9760L;
    private static final long USER_B = 9761L;
    private static final long BUYER_ID = 9762L;
    private static final long SELLER_A = 9760L;
    private static final long SELLER_B = 9761L;
    private static final long CATEGORY_ID = 9760L;
    private static final long PRODUCT_PA1 = 9760L;
    private static final long PRODUCT_PA2 = 9761L;
    private static final long PRODUCT_PA3 = 9762L;
    private static final long PRODUCT_PB = 9763L;
    private static final long GROUP_COLOR = 9760L;
    private static final long GROUP_DEFAULT = 9761L;
    private static final long VALUE_BLACK = 9760L;
    private static final long VALUE_RED = 9761L;
    private static final long VALUE_DEFAULT = 9762L;
    private static final long VARIANT_VA1 = 9760L;
    private static final long VARIANT_VA2 = 9761L;
    private static final long VARIANT_VA3 = 9762L;
    private static final long VARIANT_VB1 = 9763L;
    private static final long VARIANT_VD1 = 9764L;
    private static final long VARIANT_VPB = 9765L;

    private static final String PA1_PID = pid("prd_", "SIQPA1");
    private static final String PA2_PID = pid("prd_", "SIQPA2");
    private static final String PA3_PID = pid("prd_", "SIQPA3");
    private static final String PB_PID = pid("prd_", "SIQPB");
    private static final String VA1_PID = pid("var_", "SIQVA1");
    private static final String VA2_PID = pid("var_", "SIQVA2");
    private static final String VA3_PID = pid("var_", "SIQVA3");
    private static final String VB1_PID = pid("var_", "SIQVB1");
    private static final String VD1_PID = pid("var_", "SIQVD1");
    private static final String VPB_PID = pid("var_", "SIQVPB");
    private static final String KEYWORD_LIMIT_EXCEEDED = "K".repeat(51);

    /** 행 키 화이트리스트 — 필드가 늘면 여기와 SellerInventorySummaryResponse를 함께 바꿔야 한다. */
    private static final Set<String> SUMMARY_KEYS = Set.of("variantPublicId", "productPublicId", "productName", "optionLabel",
            "sellerSku", "quantityOnHand", "quantityReserved", "quantityAvailable", "updatedAt");
    /** 셀러 노출 금지 수동 목록(내부 PK·셀러 식별·공급가·판매기간). */
    private static final Set<String> MANUAL_FORBIDDEN_KEYS = Set.of("supplyPrice", "sellerPublicId", "sellerName", "saleStartAt",
            "saleEndAt", "sellerId", "id", "productId", "variantId", "inventoryId");
    /** 금지 키 = (관리자 상품 응답 DTO 2종 선언 필드 − 셀러 허용 키) ∪ 수동 목록(90-B-1 collectRecordKeys 패턴). */
    private static final Set<String> FORBIDDEN_KEYS = forbiddenKeys();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ObjectMapper objectMapper;

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

    @Test
    @DisplayName("T1 인가: 비인증 401 · 구매자 403 · 셀러 200")
    void authorization() throws Exception {
        mockMvc.perform(get(LIST_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2 목록: 셀러 A는 자기 variant VB1·VA1·VA2만(삭제 VA3·삭제 상품 VD1·타 셀러 VPB 제외)·옵션 라벨·재고 3수치·키 화이트리스트·금지 키 0")
    void list_returnsOwnVariantsOnly() throws Exception {
        String body = mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[*].variantPublicId", contains(VB1_PID, VA1_PID, VA2_PID)))
                .andExpect(jsonPath("$.items[0].productPublicId").value(PA2_PID))
                .andExpect(jsonPath("$.items[0].productName").value("재고상품 베타"))
                .andExpect(jsonPath("$.items[0].optionLabel").doesNotExist())
                .andExpect(jsonPath("$.items[0].sellerSku").value("SKU-BETA"))
                .andExpect(jsonPath("$.items[0].quantityOnHand").value(5))
                .andExpect(jsonPath("$.items[1].productPublicId").value(PA1_PID))
                .andExpect(jsonPath("$.items[1].productName").value("재고상품 알파"))
                .andExpect(jsonPath("$.items[1].optionLabel").value("색상: 검정"))
                .andExpect(jsonPath("$.items[1].sellerSku").value("SKU-BLK"))
                .andExpect(jsonPath("$.items[1].quantityOnHand").value(10))
                .andExpect(jsonPath("$.items[1].quantityReserved").value(2))
                .andExpect(jsonPath("$.items[1].quantityAvailable").value(8))
                .andExpect(jsonPath("$.items[2].optionLabel").value("색상: 빨강"))
                .andExpect(jsonPath("$.items[2].quantityAvailable").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode items = objectMapper.readTree(body).get("items");
        for (JsonNode row : items) {
            // NON_NULL 직렬화라 null 필드(단순상품 optionLabel)는 생략된다 → 키 집합은 화이트리스트의 부분집합.
            assertThat(SUMMARY_KEYS).containsAll(keysOf(row));
            assertThat(keysOf(row)).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
            assertThat(row.get("updatedAt").asText()).endsWith("+09:00");
        }
        assertThat(keysOf(items.get(1))).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(body).doesNotContain(VA3_PID).doesNotContain(VD1_PID).doesNotContain(VPB_PID).doesNotContain("타셀러재고");

        // 셀러 B 관점: 자기 variant VPB만.
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].variantPublicId").value(VPB_PID))
                .andExpect(jsonPath("$.items[0].productPublicId").value(PB_PID));
    }

    @Test
    @DisplayName("T3 필터: keyword 상품명·sellerSku 부분일치(escape) · productPublicId 상품 한정 · 타 셀러/미존재 상품 id는 빈 결과")
    void list_filters() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "알파"))
                .andExpect(jsonPath("$.items[*].variantPublicId", contains(VA1_PID, VA2_PID)));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "SKU-RED"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].variantPublicId").value(VA2_PID));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "SKU-B"))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].variantPublicId", contains(VB1_PID, VA1_PID)));
        // LIKE 와일드카드는 리터럴 매칭(escape) — '_'는 SKU에 없으므로 0.
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", "SKU_B"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("productPublicId", PA1_PID))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].variantPublicId", contains(VA1_PID, VA2_PID)));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("productPublicId", PA1_PID).param("keyword", "빨강"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("productPublicId", PB_PID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("productPublicId", PA3_PID))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("keyword", KEYWORD_LIMIT_EXCEEDED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T4 페이지네이션 경계: size=2 page 0(hasNext) · page 1(마지막) · page 9(빈)")
    void list_paginationBoundaries() throws Exception {
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("size", "2").param("page", "0"))
                .andExpect(jsonPath("$.items[*].variantPublicId", contains(VB1_PID, VA1_PID)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items[*].variantPublicId", contains(VA2_PID)))
                .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)).param("size", "2").param("page", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    @DisplayName("T5 D-190: SUSPENDED 셀러 목록 200(조회 허용)")
    void suspended_returns200() throws Exception {
        cleanup();
        seedAll(SellerStatus.SUSPENDED);

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3));
    }

    @ParameterizedTest(name = "{0} 셀러 GET inventories → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T6 D-190: 세션 불가 상태 → 401 UNAUTHENTICATED")
    void sessionDenied_returns401(SellerStatus status) throws Exception {
        cleanup();
        seedAll(status);

        mockMvc.perform(get(LIST_URL).headers(authHeaders.seller(USER_A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- seed·helpers ----------

    private void seedAll(SellerStatus sellerAStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSellerWithOwner(USER_A, SELLER_A, "SIQUSA", "SIQSLA", "재고셀러A", sellerAStatus);
                seedSellerWithOwner(USER_B, SELLER_B, "SIQUSB", "SIQSLB", "재고셀러B", SellerStatus.ACTIVE);
                jdbc.update("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) "
                        + "VALUES (?, '재고카테고리', 0, 0, NOW(6), NOW(6))", CATEGORY_ID);

                seedProduct(PRODUCT_PA1, PA1_PID, SELLER_A, "재고상품 알파", null);
                seedProduct(PRODUCT_PA2, PA2_PID, SELLER_A, "재고상품 베타", null);
                seedProduct(PRODUCT_PA3, PA3_PID, SELLER_A, "삭제상품 델타", "2026-03-03 09:00:00");
                seedProduct(PRODUCT_PB, PB_PID, SELLER_B, "타셀러재고", null);

                seedOptionGroup(GROUP_COLOR, PRODUCT_PA1, "색상");
                seedOptionValue(VALUE_BLACK, GROUP_COLOR, "검정", 0);
                seedOptionValue(VALUE_RED, GROUP_COLOR, "빨강", 1);
                seedVariant(VARIANT_VA1, VA1_PID, PRODUCT_PA1, "VC-BLK", "SKU-BLK", 0, VALUE_BLACK, null);
                seedVariant(VARIANT_VA2, VA2_PID, PRODUCT_PA1, "VC-RED", "SKU-RED", 1, VALUE_RED, null);
                seedVariant(VARIANT_VA3, VA3_PID, PRODUCT_PA1, "VC-DEL", "SKU-DEL", 2, VALUE_RED, "2026-03-03 09:00:00");
                seedInventory(VARIANT_VA1, 10, 2);
                seedInventory(VARIANT_VA2, 0, 0);
                seedInventory(VARIANT_VA3, 1, 0);

                seedOptionGroup(GROUP_DEFAULT, PRODUCT_PA2, "DEFAULT");
                seedOptionValue(VALUE_DEFAULT, GROUP_DEFAULT, "DEFAULT", 0);
                seedVariant(VARIANT_VB1, VB1_PID, PRODUCT_PA2, "VC-BETA", "SKU-BETA", 0, VALUE_DEFAULT, null);
                seedInventory(VARIANT_VB1, 5, 0);

                // 삭제 상품의 variant(활성)·타 셀러 variant — FK 검사 off라 옵션값은 DEFAULT 공유.
                seedVariant(VARIANT_VD1, VD1_PID, PRODUCT_PA3, "VC-DELTA", "SKU-DELTA", 0, VALUE_DEFAULT, null);
                seedInventory(VARIANT_VD1, 7, 0);
                seedVariant(VARIANT_VPB, VPB_PID, PRODUCT_PB, "VC-B", "SKU-B", 0, VALUE_DEFAULT, null);
                seedInventory(VARIANT_VPB, 4, 0);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSellerWithOwner(long userId, long sellerId, String userTag, String sellerTag, String companyName,
            SellerStatus status) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", userTag));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", sellerTag), companyName, status.name());
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", userId, sellerId);
    }

    private void seedProduct(long id, String publicId, long sellerId, String name, String deletedAt) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, is_soldout_manual, base_price, "
                + "supply_price, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, 'SALE', 0, 10000, 9999, NOW(6), NOW(6), ?)",
                id, publicId, sellerId, CATEGORY_ID, name, deletedAt);
    }

    private void seedOptionGroup(long id, long productId, String name) {
        jdbc.update("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, NOW(6), NOW(6))", id, productId, name);
    }

    private void seedOptionValue(long id, long groupId, String value, int displayOrder) {
        jdbc.update("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))", id, groupId, value, displayOrder);
    }

    private void seedVariant(long id, String publicId, long productId, String variantCode, String sellerSku, int displayOrder,
            long option1ValueId, String deletedAt) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, seller_sku, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, 0, 'SALE', 0, ?, ?, NOW(6), NOW(6), ?)",
                id, publicId, productId, variantCode, sellerSku, displayOrder, option1ValueId, deletedAt);
    }

    private void seedInventory(long variantId, int onHand, int reserved) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))", variantId, variantId, onHand, reserved, onHand - reserved);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM inventory WHERE id IN (?, ?, ?, ?, ?, ?)",
                        VARIANT_VA1, VARIANT_VA2, VARIANT_VA3, VARIANT_VB1, VARIANT_VD1, VARIANT_VPB);
                jdbc.update("DELETE FROM product_variant WHERE id IN (?, ?, ?, ?, ?, ?)",
                        VARIANT_VA1, VARIANT_VA2, VARIANT_VA3, VARIANT_VB1, VARIANT_VD1, VARIANT_VPB);
                jdbc.update("DELETE FROM product_option_value WHERE id IN (?, ?, ?)", VALUE_BLACK, VALUE_RED, VALUE_DEFAULT);
                jdbc.update("DELETE FROM product_option_group WHERE id IN (?, ?)", GROUP_COLOR, GROUP_DEFAULT);
                jdbc.update("DELETE FROM product WHERE id IN (?, ?, ?, ?)", PRODUCT_PA1, PRODUCT_PA2, PRODUCT_PA3, PRODUCT_PB);
                jdbc.update("DELETE FROM category WHERE id = ?", CATEGORY_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?)", USER_A, USER_B);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", USER_A, USER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static Set<String> forbiddenKeys() {
        Set<String> adminKeys = new LinkedHashSet<>();
        collectRecordKeys(AdminProductSummaryResponse.class, adminKeys);
        collectRecordKeys(AdminProductDetailResponse.class, adminKeys);
        adminKeys.removeAll(SUMMARY_KEYS);
        adminKeys.addAll(MANUAL_FORBIDDEN_KEYS);
        return Set.copyOf(adminKeys);
    }

    /** record 컴포넌트명을 모으고, 컴포넌트 타입이 record이거나 List&lt;record&gt;면 재귀한다(90-B-1 패턴). */
    private static void collectRecordKeys(Class<?> type, Set<String> into) {
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            into.add(component.getName());
            Class<?> nested = component.getType();
            if (List.class.isAssignableFrom(nested) && component.getGenericType() instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                nested = element;
            }
            if (nested != type) {
                collectRecordKeys(nested, into);
            }
        }
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

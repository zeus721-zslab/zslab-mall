package com.zslab.mall.checkout;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 옵션 라벨 표시·스냅샷 통합 테스트(Track 75·D-164). 실 DB에서 (1) 장바구니 조회의 현재 옵션명 (2) 주문 생성 시
 * order_item.option_label 스냅샷 (3) 주문 후 옵션값 변경에도 주문 상세 라벨 유지 (4) V20 이전 주문(NULL) → 응답 생략을 검증한다.
 *
 * <p>{@link CheckoutIntegrationTest} 패턴(단일 TX·{@code SET FOREIGN_KEY_CHECKS=0})을 따르되 옵션 그룹·값 행을 실제로 시딩한다.
 * 옵션 상품(색상×사이즈 2축)과 단순상품(DEFAULT sentinel) 각 1건.
 */
@AutoConfigureMockMvc
@Transactional
class OptionLabelIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 1L;
    private static final long SELLER_ID = 7500L;
    private static final String SELLER_PID = "slr_0000000000000000000000T75S";
    private static final long OPTION_PRODUCT_ID = 7501L;
    private static final String OPTION_PRODUCT_PID = "prd_0000000000000000000000T75O";
    private static final long SIMPLE_PRODUCT_ID = 7502L;
    private static final String SIMPLE_PRODUCT_PID = "prd_0000000000000000000000T75P";
    private static final long COLOR_GROUP_ID = 7510L;
    private static final long SIZE_GROUP_ID = 7511L;
    private static final long DEFAULT_GROUP_ID = 7512L;
    private static final long BLACK_VALUE_ID = 7520L;
    private static final long MEDIUM_VALUE_ID = 7521L;
    private static final long DEFAULT_VALUE_ID = 7522L;
    private static final long OPTION_VARIANT_ID = 7530L;
    private static final String OPTION_VARIANT_PID = "var_0000000000000000000000T75O";
    private static final long SIMPLE_VARIANT_ID = 7531L;
    private static final String SIMPLE_VARIANT_PID = "var_0000000000000000000000T75P";
    private static final String EXPECTED_LABEL = "색상: 블랙 / 사이즈: M";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthHeaders authHeaders;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        execute("SET FOREIGN_KEY_CHECKS = 0");
        execute("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (" + SELLER_ID + ", '" + SELLER_PID + "', '옵션샵', '대표', 'ACTIVE', NOW(6), NOW(6))");
        seedProduct(OPTION_PRODUCT_ID, OPTION_PRODUCT_PID, "옵션상품");
        seedProduct(SIMPLE_PRODUCT_ID, SIMPLE_PRODUCT_PID, "단순상품");
        seedOptionGroup(COLOR_GROUP_ID, OPTION_PRODUCT_ID, "색상", 0);
        seedOptionGroup(SIZE_GROUP_ID, OPTION_PRODUCT_ID, "사이즈", 1);
        seedOptionGroup(DEFAULT_GROUP_ID, SIMPLE_PRODUCT_ID, "DEFAULT", 0);
        seedOptionValue(BLACK_VALUE_ID, COLOR_GROUP_ID, "블랙");
        seedOptionValue(MEDIUM_VALUE_ID, SIZE_GROUP_ID, "M");
        seedOptionValue(DEFAULT_VALUE_ID, DEFAULT_GROUP_ID, "DEFAULT");
        seedVariant(OPTION_VARIANT_ID, OPTION_VARIANT_PID, OPTION_PRODUCT_ID, BLACK_VALUE_ID, MEDIUM_VALUE_ID);
        seedVariant(SIMPLE_VARIANT_ID, SIMPLE_VARIANT_PID, SIMPLE_PRODUCT_ID, DEFAULT_VALUE_ID, null);
        entityManager.flush();
    }

    @AfterEach
    void restoreForeignKeyChecks() {
        execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    @DisplayName("GET /cart: 옵션 상품은 '색상: 블랙 / 사이즈: M'·단순상품(DEFAULT)은 optionLabel 생략")
    void getCart_optionLabel() throws Exception {
        seedCartItem(OPTION_VARIANT_ID, OPTION_VARIANT_PID);
        seedCartItem(SIMPLE_VARIANT_ID, SIMPLE_VARIANT_PID);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/cart").headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.variantPublicId == '" + OPTION_VARIANT_PID + "')].optionLabel")
                        .value(EXPECTED_LABEL))
                .andExpect(jsonPath("$.items[?(@.variantPublicId == '" + SIMPLE_VARIANT_PID + "')].optionLabel")
                        .isEmpty());
    }

    @Test
    @DisplayName("POST /orders(직접주문): 옵션 상품 order_item.option_label 스냅샷 저장·주문 상세 노출")
    void checkout_snapshotsOptionLabel() throws Exception {
        String orderPublicId = performDirectCheckout(OPTION_PRODUCT_PID, OPTION_VARIANT_PID);

        assertOptionLabelInDatabase(orderPublicId, EXPECTED_LABEL);
        mockMvc.perform(get("/api/v1/orders/" + orderPublicId).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].items[0].optionLabel").value(EXPECTED_LABEL));
    }

    @Test
    @DisplayName("POST /orders(직접주문): 단순상품(DEFAULT) → option_label NULL·응답 생략")
    void checkout_simpleProduct_nullLabel() throws Exception {
        String orderPublicId = performDirectCheckout(SIMPLE_PRODUCT_PID, SIMPLE_VARIANT_PID);

        assertOptionLabelInDatabase(orderPublicId, null);
        mockMvc.perform(get("/api/v1/orders/" + orderPublicId).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].items[0].productName").value("단순상품"))
                .andExpect(jsonPath("$.sellers[0].items[0].optionLabel").doesNotExist());
    }

    @Test
    @DisplayName("POST /cart/checkout(장바구니 결제): 내부 id 해소 경로도 option_label 스냅샷 저장")
    void cartCheckout_snapshotsOptionLabel() throws Exception {
        seedCartItem(OPTION_VARIANT_ID, OPTION_VARIANT_PID);
        entityManager.flush();
        entityManager.clear();

        String location = mockMvc.perform(post("/api/v1/cart/checkout").headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CART_CHECKOUT_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        entityManager.flush();
        entityManager.clear();

        assertOptionLabelInDatabase(location.substring(location.lastIndexOf('/') + 1), EXPECTED_LABEL);
    }

    @Test
    @DisplayName("주문 후 옵션값·그룹명 변경: 주문 상세 라벨은 주문 시점 스냅샷 유지")
    void getOrder_labelUnchangedAfterOptionEdit() throws Exception {
        String orderPublicId = performDirectCheckout(OPTION_PRODUCT_PID, OPTION_VARIANT_PID);

        execute("UPDATE product_option_value SET value = '화이트' WHERE id = " + BLACK_VALUE_ID);
        execute("UPDATE product_option_group SET name = '컬러' WHERE id = " + COLOR_GROUP_ID);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/orders/" + orderPublicId).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].items[0].optionLabel").value(EXPECTED_LABEL));
    }

    @Test
    @DisplayName("기존 주문(option_label NULL·V20 이전): 주문 상세에서 optionLabel 생략·200")
    void getOrder_legacyOrder_noLabel() throws Exception {
        String orderPublicId = performDirectCheckout(OPTION_PRODUCT_PID, OPTION_VARIANT_PID);
        execute("UPDATE order_item SET option_label = NULL WHERE order_id = "
                + "(SELECT id FROM `order` WHERE public_id = '" + orderPublicId + "')");
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/orders/" + orderPublicId).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].items[0].productName").value("옵션상품"))
                .andExpect(jsonPath("$.sellers[0].items[0].optionLabel").doesNotExist());
    }

    // ==================== 헬퍼 ====================

    private static final String SHIPPING_JSON = """
            "shippingAddress": {
              "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
              "zonecode": "06236", "addressRoad": "서울 강남대로 1", "addressDetail": "101호"
            },
            "method": "CARD"
            """;

    private static final String CART_CHECKOUT_BODY = "{" + SHIPPING_JSON + "}";

    private String performDirectCheckout(String productPublicId, String variantPublicId) throws Exception {
        String body = "{ \"items\": [ { \"productId\": \"" + productPublicId + "\", \"variantId\": \""
                + variantPublicId + "\", \"quantity\": 1 } ], " + SHIPPING_JSON + "}";
        String location = mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        entityManager.flush();
        entityManager.clear();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void assertOptionLabelInDatabase(String orderPublicId, String expected) {
        Object stored = entityManager.createNativeQuery(
                        "SELECT oi.option_label FROM order_item oi JOIN `order` o ON oi.order_id = o.id "
                                + "WHERE o.public_id = :publicId")
                .setParameter("publicId", orderPublicId)
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThat(stored).isEqualTo(expected);
    }

    private void seedProduct(long id, String publicId, String name) {
        execute("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (" + id + ", '" + publicId + "', " + SELLER_ID + ", 1, '" + name + "', 'SALE', 10000, NOW(6), NOW(6))");
    }

    private void seedOptionGroup(long id, long productId, String name, int displayOrder) {
        execute("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                + "VALUES (" + id + ", " + productId + ", '" + name + "', " + displayOrder + ", NOW(6), NOW(6))");
    }

    private void seedOptionValue(long id, long groupId, String value) {
        execute("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                + "VALUES (" + id + ", " + groupId + ", '" + value + "', 0, NOW(6), NOW(6))");
    }

    private void seedVariant(long id, String publicId, long productId, long option1ValueId, Long option2ValueId) {
        execute("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, option2_value_id, created_at, updated_at) "
                + "VALUES (" + id + ", '" + publicId + "', " + productId + ", 'V" + id + "', 0, 'SALE', 0, 0, "
                + option1ValueId + ", " + option2ValueId + ", NOW(6), NOW(6))");
        execute("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (" + id + ", " + id + ", 100, 0, 100, NOW(6), NOW(6))");
    }

    private void seedCartItem(long variantId, String variantPublicId) {
        execute("INSERT INTO cart_item (user_id, variant_id, variant_public_id, quantity, selected, created_at, updated_at) "
                + "VALUES (" + BUYER_ID + ", " + variantId + ", '" + variantPublicId + "', 1, 1, NOW(6), NOW(6))");
    }

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }
}

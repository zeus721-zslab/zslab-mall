package com.zslab.mall.product;

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
 * 구매 가능 판정 단일화(Track 76·ProductPurchasePolicy) 회귀 — 분산 6지점(카탈로그 목록/상세·장바구니 담기/조회·주문 생성·재결제)이
 * 판매기간 밖·상품 수동품절·삭제를 동일하게 차단하는지 HTTP 경로로 검증한다(실 MariaDB·CheckoutIntegrationTest 시딩 패턴).
 *
 * <p>단일 트랜잭션(@Transactional) + FK_CHECKS=0으로 seller·product·variant·inventory만 시딩하며 테스트 종료 시 롤백된다.
 * 각 테스트는 product 컬럼(sale_start_at·sale_end_at·is_soldout_manual·deleted_at)을 UPDATE한 뒤 flush·clear로 1차 캐시를 비운다.
 */
@AutoConfigureMockMvc
@Transactional
class ProductPurchasePolicyIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 7601L;
    private static final long PRODUCT_ID = 7600L;
    private static final long VARIANT_ID = 7600L;
    private static final String SELLER_PID = "slr_0000000000000000000000T760";
    private static final String PRODUCT_PID = "prd_0000000000000000000000T760";
    private static final String VARIANT_PID = "var_0000000000000000000000T760";

    private static final String CHECKOUT_BODY = """
            {
              "items": [ { "productId": "%s", "variantId": "%s", "quantity": 1 } ],
              "shippingAddress": {
                "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                "zonecode": "06236", "addressRoad": "서울 강남대로 1", "addressDetail": "101호"
              },
              "method": "CARD"
            }
            """.formatted(PRODUCT_PID, VARIANT_PID);
    private static final String CART_ADD_BODY = "{\"variantPublicId\":\"" + VARIANT_PID + "\",\"quantity\":1}";

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
                + "VALUES (" + PRODUCT_ID + ", '" + SELLER_PID + "', '정책샵', '대표', 'ACTIVE', NOW(6), NOW(6))");
        execute("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (" + PRODUCT_ID + ", '" + PRODUCT_PID + "', " + PRODUCT_ID + ", 1, '정책상품', 'SALE', 8000, NOW(6), NOW(6))");
        execute("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES (" + VARIANT_ID + ", '" + VARIANT_PID + "', " + PRODUCT_ID + ", 'VCODE', 0, 'SALE', 0, 0, 1, NOW(6), NOW(6))");
        execute("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (" + VARIANT_ID + ", " + VARIANT_ID + ", 100, 0, 100, NOW(6), NOW(6))");
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void restoreForeignKeyChecks() {
        // @Transactional 공유 커넥션 내 FK 복원 — 롤백 전 동일 커넥션에서 세션 변수 정리(LT-02)
        execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    // ==================== 기준선 ====================

    @Test
    @DisplayName("기준선: SALE·기간 무제한·재고 → 목록 노출·상세 saleStopped=false·담기 201·주문 201")
    void baseline_allPathsAllow() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("keyword", "정책상품"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].soldOut").value(false));
        mockMvc.perform(get("/api/v1/products/" + PRODUCT_PID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleStopped").value(false))
                .andExpect(jsonPath("$.soldOut").value(false));
        addToCart().andExpect(status().isCreated());
        checkout().andExpect(status().isCreated());
    }

    // ==================== 판매기간 밖 ====================

    @Test
    @DisplayName("판매 종료(sale_end_at 과거): 목록 미노출·상세 200 saleStopped=true·담기 422·주문 422 PRODUCT_NOT_ON_SALE")
    void salePeriodEnded_blocksAllPaths() throws Exception {
        updateProduct("sale_end_at = DATE_SUB(NOW(6), INTERVAL 1 DAY)");

        mockMvc.perform(get("/api/v1/products").param("keyword", "정책상품"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get("/api/v1/products/" + PRODUCT_PID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleStopped").value(true));
        addToCart().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_PURCHASABLE"));
        checkout().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"))
                .andExpect(jsonPath("$.detail").value("PRODUCT_NOT_ON_SALE"));
    }

    @Test
    @DisplayName("판매 예정(sale_start_at 미래): 목록 미노출·담기 422 / 시작 시각 과거·종료 미래는 노출·담기 201")
    void salePeriodNotStarted_blocks_andWithinPeriodAllows() throws Exception {
        updateProduct("sale_start_at = DATE_ADD(NOW(6), INTERVAL 1 DAY)");
        mockMvc.perform(get("/api/v1/products").param("keyword", "정책상품"))
                .andExpect(jsonPath("$.totalCount").value(0));
        addToCart().andExpect(status().isUnprocessableEntity());

        updateProduct("sale_start_at = DATE_SUB(NOW(6), INTERVAL 1 DAY), sale_end_at = DATE_ADD(NOW(6), INTERVAL 1 DAY)");
        mockMvc.perform(get("/api/v1/products").param("keyword", "정책상품"))
                .andExpect(jsonPath("$.totalCount").value(1));
        addToCart().andExpect(status().isCreated());
    }

    // ==================== 상품 수동품절 ====================

    @Test
    @DisplayName("상품 수동품절: 목록 노출되나 soldOut=true·상세 soldOut=true/saleStopped=false·담기 422·주문 422 OUT_OF_STOCK")
    void productManualSoldOut_marksSoldOutAndBlocksPurchase() throws Exception {
        updateProduct("is_soldout_manual = 1");

        mockMvc.perform(get("/api/v1/products").param("keyword", "정책상품"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].soldOut").value(true));
        mockMvc.perform(get("/api/v1/products/" + PRODUCT_PID))
                .andExpect(jsonPath("$.soldOut").value(true))
                .andExpect(jsonPath("$.saleStopped").value(false))
                .andExpect(jsonPath("$.variants[0].soldOut").value(true));
        addToCart().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_PURCHASABLE"));
        checkout().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("OUT_OF_STOCK"));
    }

    // ==================== 장바구니 조회 enrich·재결제 ====================

    @Test
    @DisplayName("담긴 후 판매 종료: 장바구니 조회 purchasable=false / 담긴 후 상품 수동품절도 purchasable=false")
    void cartView_reflectsPolicyAfterAdding() throws Exception {
        addToCart().andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();

        updateProduct("sale_end_at = DATE_SUB(NOW(6), INTERVAL 1 DAY)");
        mockMvc.perform(get("/api/v1/cart").headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].purchasable").value(false));

        updateProduct("sale_end_at = NULL, is_soldout_manual = 1");
        mockMvc.perform(get("/api/v1/cart").headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(jsonPath("$.items[0].purchasable").value(false));

        updateProduct("is_soldout_manual = 0");
        mockMvc.perform(get("/api/v1/cart").headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(jsonPath("$.items[0].purchasable").value(true));
    }

    @Test
    @DisplayName("재결제: 주문 후 상품 수동품절 → 422 OUT_OF_STOCK / 판매 종료 → 422 PRODUCT_NOT_ON_SALE")
    void retryPayment_revalidatesWithPolicy() throws Exception {
        String location = checkout().andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        String orderPublicId = location.substring(location.lastIndexOf('/') + 1);
        entityManager.flush();
        entityManager.clear();
        execute("UPDATE payment SET status = 'FAILED' WHERE order_id = "
                + "(SELECT id FROM `order` WHERE public_id = '" + orderPublicId + "')");

        updateProduct("is_soldout_manual = 1");
        retryPayment(orderPublicId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("OUT_OF_STOCK"));

        updateProduct("is_soldout_manual = 0, sale_end_at = DATE_SUB(NOW(6), INTERVAL 1 DAY)");
        retryPayment(orderPublicId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("PRODUCT_NOT_ON_SALE"));
    }

    // ==================== 삭제 ====================

    @Test
    @DisplayName("soft-delete 상품: 목록 미노출·상세 404·담기 422·주문 404(CHECKOUT_ITEM 미존재·기존 규약)")
    void deletedProduct_hiddenEverywhere() throws Exception {
        updateProduct("deleted_at = NOW(6)");

        mockMvc.perform(get("/api/v1/products").param("keyword", "정책상품"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get("/api/v1/products/" + PRODUCT_PID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        addToCart().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_PURCHASABLE"));
        checkout().andExpect(status().isNotFound());
    }

    // ==================== helpers ====================

    private org.springframework.test.web.servlet.ResultActions addToCart() throws Exception {
        return mockMvc.perform(post("/api/v1/cart/items").headers(authHeaders.buyer(BUYER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(CART_ADD_BODY));
    }

    private org.springframework.test.web.servlet.ResultActions checkout() throws Exception {
        return mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(BUYER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(CHECKOUT_BODY));
    }

    private org.springframework.test.web.servlet.ResultActions retryPayment(String orderPublicId) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + orderPublicId + "/payments").headers(authHeaders.buyer(BUYER_ID))
                .contentType(MediaType.APPLICATION_JSON).content("{ \"method\": \"CARD\" }"));
    }

    /** 테스트 전용 정적 SQL(값은 상수 리터럴·외부 입력 없음). */
    private void updateProduct(String setClause) {
        execute("UPDATE product SET " + setClause + " WHERE id = " + PRODUCT_ID);
        entityManager.flush();
        entityManager.clear();
    }

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }
}

package com.zslab.mall.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.hibernate.Session;
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
 * 셀러 가격 변경의 주문 파급 통합 테스트(Track 90-C 외부 검토 r2c ①②③·실 MariaDB·HTTP 경유). 셀러 PUT(기본가·추가금 변경)과 구매자
 * 결제(POST orders·재결제 POST orders/{id}/payments)를 같은 흐름에서 호출해 다음을 실측한다.
 * ① 결제 시 order_item.unit_price = 결제 시점 base_price + additional_price 스냅샷
 * ② 추가금·기본가 변경 후 기존 주문 품목 unit_price·total_price·order.total_price 불변 + 새 주문은 변경 후 가격 반영
 * ③ 가격 인상 후 미결제(결제 실패) 주문 재결제 201 + 주문 금액·결제 금액이 원 스냅샷 그대로
 *
 * <p>{@code @Transactional} + {@code SET FOREIGN_KEY_CHECKS=0}(CheckoutIntegrationTest 패턴)이라 seller·user·seller_user·product 그래프만 시딩하고
 * 종료 시 롤백된다. 셀러 인증은 seller_user 매핑(SELLER_OWNER)으로 해소된다.
 */
@AutoConfigureMockMvc
@Transactional
class SellerPriceChangeSnapshotIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 1L;
    private static final long SELLER_USER_ID = 9770L;
    private static final long SELLER_ID = 9770L;
    private static final long PRODUCT_ID = 9770L;
    private static final long DEFAULT_GROUP_ID = 9770L;
    private static final long DEFAULT_VALUE_ID = 9770L;
    private static final long CATEGORY_ID = 9770L;
    private static final long VARIANT_BASE_ID = 9770L;
    private static final long VARIANT_PLUS_ID = 9771L;
    private static final String SELLER_PID = "slr_000000000000000000000SPCS1";
    private static final String PRODUCT_PID = "prd_000000000000000000000SPCS1";
    private static final String VARIANT_BASE_PID = "var_000000000000000000000SPCS1";
    private static final String VARIANT_PLUS_PID = "var_000000000000000000000SPCS2";
    private static final long BASE_PRICE = 10_000L;
    private static final long PLUS_ADDITIONAL = 500L;
    private static final long NEW_BASE_PRICE = 12_000L;
    private static final long NEW_PLUS_ADDITIONAL = 1_500L;

    private static final String SHIPPING_JSON = """
            "shippingAddress": {
              "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
              "zonecode": "06236", "addressRoad": "서울 강남대로 1", "addressDetail": "101호"
            },
            "method": "CARD"
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        execute("SET FOREIGN_KEY_CHECKS = 0");
        execute("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (" + SELLER_USER_ID + ", 'usr_000000000000000000000SPCS1', NOW(6), NOW(6))");
        execute("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                + "VALUES (" + SELLER_ID + ", '" + SELLER_PID + "', '가격셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))");
        execute("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT " + SELLER_USER_ID + ", " + SELLER_ID + ", id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'");
        execute("INSERT INTO category (id, display_name, depth, sort_order, created_at, updated_at) VALUES (" + CATEGORY_ID + ", '가격카테고리', 0, 0, NOW(6), NOW(6))");
        execute("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (" + PRODUCT_ID + ", '" + PRODUCT_PID + "', " + SELLER_ID + ", " + CATEGORY_ID + ", '가격상품', 'SALE', " + BASE_PRICE + ", NOW(6), NOW(6))");
        execute("INSERT INTO product_option_group (id, product_id, name, display_order, created_at, updated_at) "
                + "VALUES (" + DEFAULT_GROUP_ID + ", " + PRODUCT_ID + ", 'DEFAULT', 0, NOW(6), NOW(6))");
        execute("INSERT INTO product_option_value (id, option_group_id, value, display_order, created_at, updated_at) "
                + "VALUES (" + DEFAULT_VALUE_ID + ", " + DEFAULT_GROUP_ID + ", 'DEFAULT', 0, NOW(6), NOW(6))");
        seedVariant(VARIANT_BASE_ID, VARIANT_BASE_PID, "V-BASE", 0);
        seedVariant(VARIANT_PLUS_ID, VARIANT_PLUS_PID, "V-PLUS", PLUS_ADDITIONAL);
        entityManager.flush();
    }

    @AfterEach
    void restoreForeignKeyChecks() {
        execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    @DisplayName("r2c① 결제: order_item.unit_price = 결제 시점 base_price + additional_price 스냅샷(기본 10000·추가금 500 → 10500)")
    void checkout_snapshotsUnitPrice() throws Exception {
        String orderPublicId = checkout(VARIANT_PLUS_PID, 2);

        assertThat(unitPrice(orderPublicId)).isEqualTo(BASE_PRICE + PLUS_ADDITIONAL);
        assertThat(itemTotal(orderPublicId)).isEqualTo((BASE_PRICE + PLUS_ADDITIONAL) * 2);
        assertThat(orderTotal(orderPublicId)).isEqualTo((BASE_PRICE + PLUS_ADDITIONAL) * 2);
    }

    @Test
    @DisplayName("r2c② 셀러 PUT variants(추가금 500→1500)·PUT 기본정보(기본가 10000→12000) 후: 기존 주문 스냅샷 불변 · 새 주문은 13500 반영")
    void priceChange_keepsExistingOrderAndAppliesToNew() throws Exception {
        String existingOrder = checkout(VARIANT_PLUS_PID, 1);
        assertThat(unitPrice(existingOrder)).isEqualTo(BASE_PRICE + PLUS_ADDITIONAL);

        sellerChangePrices();

        // 기존 주문은 스냅샷 그대로(재조회 없음)
        assertThat(unitPrice(existingOrder)).isEqualTo(BASE_PRICE + PLUS_ADDITIONAL);
        assertThat(orderTotal(existingOrder)).isEqualTo(BASE_PRICE + PLUS_ADDITIONAL);
        // 새 주문은 변경 후 가격(12000 + 1500)
        String newOrder = checkout(VARIANT_PLUS_PID, 1);
        assertThat(unitPrice(newOrder)).isEqualTo(NEW_BASE_PRICE + NEW_PLUS_ADDITIONAL);
        assertThat(orderTotal(newOrder)).isEqualTo(NEW_BASE_PRICE + NEW_PLUS_ADDITIONAL);
    }

    @Test
    @DisplayName("r2c③ 가격 인상 후 미결제(결제 실패) 주문 재결제 → 201 · order_item·order·payment 금액이 원 스냅샷 그대로(재산정 없음)")
    void retryAfterPriceIncrease_keepsOriginalAmount() throws Exception {
        String orderPublicId = checkout(VARIANT_PLUS_PID, 1);
        long originalTotal = orderTotal(orderPublicId);
        execute("UPDATE payment SET status = 'FAILED' WHERE order_id = (SELECT id FROM `order` WHERE public_id = '" + orderPublicId + "')");
        entityManager.flush();
        entityManager.clear();

        sellerChangePrices();

        mockMvc.perform(post("/api/v1/orders/" + orderPublicId + "/payments").headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"method\": \"CARD\" }"))
                .andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();

        assertThat(unitPrice(orderPublicId)).isEqualTo(BASE_PRICE + PLUS_ADDITIONAL);
        assertThat(orderTotal(orderPublicId)).isEqualTo(originalTotal);
        List<Long> paymentAmounts = paymentAmounts(orderPublicId);
        assertThat(paymentAmounts).hasSize(2); // 실패 1 + 재결제 1
        assertThat(paymentAmounts).allMatch(amount -> amount == originalTotal);
    }

    // ==================== 헬퍼 ====================

    /** 셀러 A로 추가금(500→1500)·기본가(10000→12000)를 HTTP PUT으로 바꾼다(관리자 승인 없이 즉시 반영·90-C-2 계약). */
    private void sellerChangePrices() throws Exception {
        String variantsBody = "{\"variants\":[{\"variantPublicId\":\"" + VARIANT_PLUS_PID + "\",\"variantCode\":\"V-PLUS\",\"additionalPrice\":"
                + NEW_PLUS_ADDITIONAL + ",\"status\":\"SALE\",\"soldoutManual\":false,\"displayOrder\":1,\"initialStock\":0}]}";
        mockMvc.perform(put("/api/v1/seller/products/" + PRODUCT_PID + "/variants").headers(authHeaders.seller(SELLER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(variantsBody))
                .andExpect(status().isOk());
        String basicBody = "{\"categoryId\":" + CATEGORY_ID + ",\"name\":\"가격상품\",\"basePrice\":" + NEW_BASE_PRICE + "}";
        mockMvc.perform(put("/api/v1/seller/products/" + PRODUCT_PID).headers(authHeaders.seller(SELLER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(basicBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(NEW_BASE_PRICE));
        entityManager.flush();
        entityManager.clear();
    }

    private String checkout(String variantPublicId, int quantity) throws Exception {
        String body = "{ \"items\": [ { \"productId\": \"" + PRODUCT_PID + "\", \"variantId\": \"" + variantPublicId
                + "\", \"quantity\": " + quantity + " } ], " + SHIPPING_JSON + "}";
        String location = mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        entityManager.flush();
        entityManager.clear();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private long unitPrice(String orderPublicId) {
        return scalar("SELECT oi.unit_price FROM order_item oi JOIN `order` o ON oi.order_id = o.id WHERE o.public_id = :publicId", orderPublicId);
    }

    private long itemTotal(String orderPublicId) {
        return scalar("SELECT oi.total_price FROM order_item oi JOIN `order` o ON oi.order_id = o.id WHERE o.public_id = :publicId", orderPublicId);
    }

    private long orderTotal(String orderPublicId) {
        return scalar("SELECT o.total_price FROM `order` o WHERE o.public_id = :publicId", orderPublicId);
    }

    private List<Long> paymentAmounts(String orderPublicId) {
        // Hibernate Session의 타입 지정 NativeQuery<Long>을 써 Object 캐스팅 없이 읽는다.
        return entityManager.unwrap(Session.class)
                .createNativeQuery("SELECT p.amount FROM payment p JOIN `order` o ON p.order_id = o.id WHERE o.public_id = :publicId ORDER BY p.id", Long.class)
                .setParameter("publicId", orderPublicId)
                .getResultList();
    }

    private long scalar(String sql, String orderPublicId) {
        return entityManager.unwrap(Session.class).createNativeQuery(sql, Long.class).setParameter("publicId", orderPublicId).getSingleResult();
    }

    private void seedVariant(long id, String publicId, String code, long additionalPrice) {
        execute("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, is_soldout_manual, display_order, "
                + "option1_value_id, created_at, updated_at) VALUES (" + id + ", '" + publicId + "', " + PRODUCT_ID + ", '" + code + "', "
                + additionalPrice + ", 'SALE', 0, " + (id - VARIANT_BASE_ID) + ", " + DEFAULT_VALUE_ID + ", NOW(6), NOW(6))");
        execute("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (" + id + ", " + id + ", 100, 0, 100, NOW(6), NOW(6))");
    }

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }
}

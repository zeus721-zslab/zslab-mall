package com.zslab.mall.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 생성 실패 경로(자체 평점 5-1) 통합 테스트(실 MariaDB·MockMvc). {@code CheckoutIntegrationTest}의 "상품 STOPPED → 422" 조건을
 * 커밋 경로에서 재현해, 주문 접수 알림(OrderPlaced AFTER_COMMIT 소비)까지 미발생을 관찰한다.
 *
 * <p><b>트랜잭션</b>: {@code CheckoutIntegrationTest}는 {@code @Transactional}이라 AFTER_COMMIT 알림이 원래 발화하지 않는다 —
 * 그래서 이 클래스는 {@code @Transactional}을 두지 않고 시드/정리를 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}
 * (LT-02 try-finally)으로 한다(MockPaymentCallbackIntegrationTest 패턴).
 */
@AutoConfigureMockMvc
class CheckoutRefusalSideEffectIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 9430L;
    private static final long SELLER_ID = 9430L;
    private static final long PRODUCT_ID = 9430L;
    private static final long VARIANT_ID = 9430L;
    private static final long INVENTORY_ID = 9430L;
    private static final long DUMMY_FK_ID = 9430L;
    private static final int INITIAL_STOCK = 100;

    private static final String PRODUCT_PID = pid("prd_", "A2BCHKPRD");
    private static final String VARIANT_PID = pid("var_", "A2BCHKVAR");
    private static final String CREATE_BODY = """
            {
              "items": [ { "productId": "%s", "variantId": "%s", "quantity": 2 } ],
              "shippingAddress": {
                "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                "zonecode": "06236", "addressRoad": "서울 강남대로 1", "addressDetail": "101호"
              },
              "method": "CARD"
            }
            """.formatted(PRODUCT_PID, VARIANT_PID);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("O1 주문 생성 실패: 상품 STOPPED → 422 ORDER_NOT_PAYABLE(PRODUCT_NOT_ON_SALE) · 주문·품목 미생성 · 재고(보유·예약·가용·이력) 불변 · 주문 접수 알림 0")
    void checkout_productStopped_refused_noSideEffects() throws Exception {
        mockMvc.perform(post("/api/v1/orders").with(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"))
                .andExpect(jsonPath("$.detail").value("PRODUCT_NOT_ON_SALE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE buyer_id = ?", Integer.class, BUYER_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE variant_id = ?", Integer.class, VARIANT_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID))
                .isEqualTo(INITIAL_STOCK);
        assertThat(jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity_available FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID))
                .isEqualTo(INITIAL_STOCK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ?", Integer.class, INVENTORY_ID))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ? AND template_code = ?",
                Integer.class, BUYER_ID, NotificationTemplateCodes.ORDER_PLACED)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ?", Integer.class, BUYER_ID))
                .isZero();
    }

    // 모든 시드·조회 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** buyer·seller(ACTIVE)·product(STOPPED·관리자 중지)·variant(SALE)·inventory(100/0/100). */
    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "A2BCHKUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, 'A2B주문셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "A2BCHKSLR"));
                // D-206 불변식(STOPPED ↔ sale_stop_source NOT NULL): 관리자 중지로 시드.
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, sale_stop_source, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'A2B주문상품', 'STOPPED', 'ADMIN', 8000, NOW(6), NOW(6))",
                        PRODUCT_ID, PRODUCT_PID, SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCA2BCHK', 2000, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, VARIANT_PID, PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                                + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID, INITIAL_STOCK, INITIAL_STOCK);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM payment WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER_ID);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER_ID);
                jdbc.update("DELETE FROM order_item WHERE variant_id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

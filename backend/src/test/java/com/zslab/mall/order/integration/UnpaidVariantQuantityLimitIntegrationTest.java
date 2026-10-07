package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자·variant 단위 미결제 예약 수량 상한(SEC-02 잔여) 통합 테스트. 미결제 주문 건수 한도(3건)만으로는 1건에 재고 전량을 담거나 같은 variant를
 * 여러 줄로 나눠 담는 우회를 막지 못한다 — 같은 구매자의 미결제 주문에 예약된 같은 variant 수량과 이번 요청 합계가 상한을 넘으면 422로 거부하고,
 * 다른 구매자의 주문은 그대로 받는지 확인한다.
 *
 * <p>스케줄러 속성 집합은 {@code UnpaidOrderLimitConcurrencyIntegrationTest}와 같게 둬 컨텍스트를 공유한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class UnpaidVariantQuantityLimitIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_A = 6481L;
    private static final long BUYER_B = 6482L;
    private static final long SELLER_ID = 6481L;
    private static final long PRODUCT_ID = 6481L;
    private static final long VARIANT_ID = 6481L;
    private static final long INVENTORY_ID = 6481L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 6481L;
    private static final int INITIAL_STOCK = 98;
    /** application.yml {@code zslab.order.max-unpaid-quantity-per-variant} 기본값. */
    private static final int LIMIT = 20;
    private static final String LIMIT_EXCEEDED_CODE = "UNPAID_VARIANT_QUANTITY_LIMIT_EXCEEDED";

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
    @DisplayName("(1) 구매자 A 1줄로 재고 전량(98개) → 422 · 예약 0 / 이어서 구매자 B 1개 → 201")
    void singleLineOverLimit_rejected_otherBuyerStillOrders() throws Exception {
        order(BUYER_A, INITIAL_STOCK)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(LIMIT_EXCEEDED_CODE));
        assertThat(reserved()).isZero();

        order(BUYER_B, 1).andExpect(status().isCreated());
        assertThat(reserved()).isEqualTo(1);
    }

    @Test
    @DisplayName("(2) 같은 variant 3줄 합계 21개 → 422 · 예약 0 / 3줄 합계 20개(상한) → 201")
    void duplicateLinesSummed_overLimitRejected_atLimitAccepted() throws Exception {
        order(BUYER_A, 7, 7, 7)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(LIMIT_EXCEEDED_CODE));
        assertThat(reserved()).isZero();

        order(BUYER_A, 7, 7, 6).andExpect(status().isCreated());
        assertThat(reserved()).isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("(3) 미결제 주문 누적: 10개 201 · 10개 201 · 1개 422(합계 21) · 예약 20 / 구매자 B 1개 → 201")
    void accumulatedAcrossUnpaidOrders_overLimitRejected() throws Exception {
        order(BUYER_A, 10).andExpect(status().isCreated());
        order(BUYER_A, 10).andExpect(status().isCreated());
        order(BUYER_A, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(LIMIT_EXCEEDED_CODE));
        assertThat(reserved()).isEqualTo(LIMIT);
        assertThat(pendingOrderCount(BUYER_A)).isEqualTo(2);

        order(BUYER_B, 1).andExpect(status().isCreated());
        assertThat(reserved()).isEqualTo(LIMIT + 1);
    }

    // ---------- 요청 ----------

    private ResultActions order(long buyerId, int... lineQuantities) throws Exception {
        String lines = Arrays.stream(lineQuantities)
                .mapToObj(quantity -> "{ \"productId\": \"%s\", \"variantId\": \"%s\", \"quantity\": %d }"
                        .formatted(pid("prd_", "UVQPRD"), pid("var_", "UVQVAR"), quantity))
                .collect(Collectors.joining(", "));
        String body = """
                {
                  "items": [ %s ],
                  "shippingAddress": {
                    "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                    "zonecode": "06236", "addressRoad": "서울 강남대로 1"
                  },
                  "method": "CARD"
                }
                """.formatted(lines);
        return mockMvc.perform(post("/api/v1/orders").with(authHeaders.buyer(buyerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------- seed·helpers ----------
    // 모든 시드·정리 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                                + "VALUES (?, ?, 'uvq-a@example.test', '수량구매자A', '010-6480-6481', NOW(6), NOW(6))",
                        BUYER_A, pid("usr_", "UVQUSRA"));
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                                + "VALUES (?, ?, 'uvq-b@example.test', '수량구매자B', '010-6480-6482', NOW(6), NOW(6))",
                        BUYER_B, pid("usr_", "UVQUSRB"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '수량셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "UVQSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, '수량상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "UVQPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCUVQ', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "UVQVAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                                + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID, INITIAL_STOCK, INITIAL_STOCK);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id IN (?, ?)", BUYER_A, BUYER_B);
                jdbc.update("DELETE FROM payment WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id IN (?, ?))", BUYER_A, BUYER_B);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id IN (?, ?))",
                        BUYER_A, BUYER_B);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id IN (?, ?))", BUYER_A, BUYER_B);
                jdbc.update("DELETE FROM `order` WHERE buyer_id IN (?, ?)", BUYER_A, BUYER_B);
                jdbc.update("DELETE FROM order_idempotency_key WHERE buyer_id IN (?, ?)", BUYER_A, BUYER_B);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", BUYER_A, BUYER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int pendingOrderCount(long buyerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE buyer_id = ? AND status = 'PENDING_PAYMENT'",
                Integer.class, buyerId);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

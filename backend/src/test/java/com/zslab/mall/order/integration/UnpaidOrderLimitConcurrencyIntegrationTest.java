package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미결제 주문 한도 동시성 통합 테스트(D-268 SEC-02·실 MariaDB). 같은 구매자가 미결제 0건에서 서로 다른 멱등 키로 주문 생성 요청을
 * 동시에 보내도 한도({@link OrderService#MAX_UNPAID_ORDERS_PER_BUYER}건)를 넘겨 생성되지 않는지 확인한다.
 *
 * <p>워커는 래치로 동시 출발한다. 구매자 행 락이 없으면 모든 요청이 커밋 전 건수(0)를 보고 통과하므로 한도를 넘겨 생성된다(RED).
 * 클래스에 {@code @Transactional}을 두지 않는다(행 락·실제 커밋 관찰). lock wait timeout을 줄이는 설정을 두지 않는다 — 대기
 * 중인 요청이 409로 끝나면 직렬화가 아니라 타임아웃을 보게 된다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class UnpaidOrderLimitConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 6471L;
    private static final long SELLER_ID = 6471L;
    private static final long PRODUCT_ID = 6471L;
    private static final long VARIANT_ID = 6471L;
    private static final long INVENTORY_ID = 6471L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 6471L;
    private static final int INITIAL_STOCK = 100;
    private static final int QUANTITY_PER_ORDER = 1;
    private static final int THREADS = 8;
    private static final int WORKER_TIMEOUT_SECONDS = 60;
    private static final String CREATED = "201";
    private static final String LIMIT_EXCEEDED = "422 UNPAID_ORDER_LIMIT_EXCEEDED";
    private static final String ORDER_BODY = """
            {
              "items": [ { "productId": "%s", "variantId": "%s", "quantity": %d } ],
              "shippingAddress": {
                "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                "zonecode": "06236", "addressRoad": "서울 강남대로 1"
              },
              "method": "CARD"
            }
            """.formatted(pid("prd_", "ULCPRD"), pid("var_", "ULCVAR"), QUANTITY_PER_ORDER);

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
    @DisplayName("SEC-02 동시 주문 8건(같은 구매자·미결제 0건·서로 다른 멱등 키) → 3건 201·5건 422 UNPAID_ORDER_LIMIT_EXCEEDED·거부분 재고 예약 0")
    void concurrentCheckout_sameBuyer_createsOnlyUpToUnpaidLimit() throws Exception {
        int limit = OrderService.MAX_UNPAID_ORDERS_PER_BUYER;

        List<String> outcomes = checkoutConcurrently();

        // 커밋된 상태부터 본다 — 직렬화가 없으면 생성 건수가 한도를 넘는다.
        assertThat(pendingPaymentOrderCount()).as("PENDING_PAYMENT 주문 수").isEqualTo(limit);
        assertThat(reserved()).as("예약 수량(거부분 0)").isEqualTo(limit * QUANTITY_PER_ORDER);
        List<String> expected = new ArrayList<>(Collections.nCopies(limit, CREATED));
        expected.addAll(Collections.nCopies(THREADS - limit, LIMIT_EXCEEDED));
        assertThat(outcomes).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---------- 동시 실행 ----------

    /** 워커 THREADS개를 래치로 동시 출발시켜 각 요청의 결과("상태" 또는 "상태 code")를 모은다. */
    private List<String> checkoutConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                String idempotencyKey = "ulc-concurrent-key-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return checkoutOutcome(idempotencyKey);
                }));
            }
            assertThat(ready.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("워커 출발 대기").isTrue();
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private String checkoutOutcome(String idempotencyKey) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/orders").with(authHeaders.buyer(BUYER_ID))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andReturn().getResponse();
        if (response.getStatus() == HttpStatus.CREATED.value()) {
            return CREATED;
        }
        String code = JsonPath.read(response.getContentAsString(StandardCharsets.UTF_8), "$.code");
        return response.getStatus() + " " + code;
    }

    // ---------- seed·helpers ----------
    // 모든 시드·정리 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                                + "VALUES (?, ?, 'ulc@example.test', '한도구매자', '010-6470-6470', NOW(6), NOW(6))",
                        BUYER_ID, pid("usr_", "ULCUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '한도셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "ULCSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, '한도상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "ULCPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCULC', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "ULCVAR"), PRODUCT_ID, DUMMY_FK_ID);
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
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM payment WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER_ID);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER_ID);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", BUYER_ID);
                jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM order_idempotency_key WHERE buyer_id = ?", BUYER_ID);
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

    private int pendingPaymentOrderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE buyer_id = ? AND status = 'PENDING_PAYMENT'",
                Integer.class, BUYER_ID);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

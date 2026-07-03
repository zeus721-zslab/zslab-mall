package com.zslab.mall.checkout;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;
import com.zslab.mall.order.service.OrderService;

/**
 * Checkout API 통합 테스트(실 MariaDB·Flyway V1~V4·validate). 신규 쿼리(findByPublicIdIn·fetch join)·멱등성 INSERT·
 * 서버 가격 산정·재검증(D-60)을 실 DB로 검증해 라이브 트랩을 차단한다(CLAUDE.md CI 트랩 방지).
 *
 * <p>단일 트랜잭션(@Transactional) + {@code SET FOREIGN_KEY_CHECKS=0}으로 상위 그래프(user·category·option_value) 없이
 * seller·product·variant·inventory만 시딩한다(기존 DataJpaTestBase FK 비활성 패턴 준용). 테스트 종료 시 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CheckoutIntegrationTest {

    private static final String SELLER_PID = "slr_0000000000000000000000IT01";
    private static final String PRODUCT_PID = "prd_0000000000000000000000IT01";
    private static final String VARIANT_PID = "var_0000000000000000000000IT01";
    private static final long VARIANT_ID = 1000L;

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

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private OrderService orderService;

    @AfterEach
    void resetSpy() {
        // @Transactional 공유 커넥션 내 FK 복원 — 트랜잭션 롤백 전 동일 커넥션에서 세션 변수 정리(LT-02)
        try {
            execute("SET FOREIGN_KEY_CHECKS = 1");
        } finally {
            Mockito.reset(orderService);
        }
    }

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

    @BeforeEach
    void seed() {
        // 단일 TX 내 FK 비활성 → 상위 그래프 없이 read 대상(seller·product·variant·inventory)만 시딩. 앱 write도 같은 TX라 FK off 적용.
        execute("SET FOREIGN_KEY_CHECKS = 0");
        execute("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (1000, '" + SELLER_PID + "', '테스트샵', '대표', 'ACTIVE', NOW(6), NOW(6))");
        execute("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (1000, '" + PRODUCT_PID + "', 1000, 1, '테스트상품', 'SALE', 8000, NOW(6), NOW(6))");
        execute("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES (1000, '" + VARIANT_PID + "', 1000, 'VCODE', 2000, 'SALE', 0, 1, 1, NOW(6), NOW(6))");
        execute("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (1000, " + VARIANT_ID + ", 100, 0, 100, NOW(6), NOW(6))");
        entityManager.flush();
    }

    @Test
    @DisplayName("POST /api/v1/orders: 신규 주문 → 201·Location·X-Trace-Id·서버 가격(20000)·PENDING payment")
    void checkout_happyPath() throws Exception {
        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.payment.status.code").value("PENDING"))
                .andExpect(jsonPath("$.payment.redirectUrl").exists());
    }

    @Test
    @DisplayName("멱등성: 동일 Idempotency-Key 재요청 → 2차는 200 캐시 반환")
    void checkout_idempotentReplay_returns200() throws Exception {
        String key = "idem-key-replay-0001";
        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("멱등성: IN_PROGRESS(order 미생성) 키 재요청 → 409")
    void checkout_inProgressKey_returns409() throws Exception {
        String key = "idem-key-inprogress-1";
        execute("INSERT INTO order_idempotency_key (buyer_id, idempotency_key, status, created_at) "
                + "VALUES (1, '" + key + "', 'IN_PROGRESS', NOW(6))");
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_PROGRESS"));
    }

    @Test
    @DisplayName("GET 단건: seller 그룹화·public_id·companyName·총액 200")
    void getSingle_sellerGrouped() throws Exception {
        String orderPublicId = performCheckout(null);

        mockMvc.perform(get("/api/v1/orders/" + orderPublicId).headers(authHeaders.buyer(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderPublicId))
                .andExpect(jsonPath("$.totalPrice").value(20000))
                .andExpect(jsonPath("$.sellers[0].sellerId").value(SELLER_PID))
                .andExpect(jsonPath("$.sellers[0].companyName").value("테스트샵"))
                .andExpect(jsonPath("$.sellers[0].items[0].productId").value(PRODUCT_PID))
                .andExpect(jsonPath("$.sellers[0].subtotal").value(20000));
    }

    @Test
    @DisplayName("GET 단건: 타인 주문 → 404 + ProblemDetail.traceId")
    void getSingle_otherBuyer_returns404() throws Exception {
        String orderPublicId = performCheckout(null);

        mockMvc.perform(get("/api/v1/orders/" + orderPublicId).headers(authHeaders.buyer(2)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("GET 목록: PagedResponse·previewTitle 200")
    void getList_returnsPaged() throws Exception {
        performCheckout(null);

        mockMvc.perform(get("/api/v1/orders").headers(authHeaders.buyer(1)).param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items[0].previewTitle").value("테스트상품"));
    }

    @Test
    @DisplayName("재결제: 직전 결제 FAILED + 재고 0 → 422 ORDER_NOT_PAYABLE(OUT_OF_STOCK)")
    void retry_outOfStock_returns422() throws Exception {
        String orderPublicId = performCheckout(null);
        failPaymentAndDepleteStock(orderPublicId);

        mockMvc.perform(post("/api/v1/orders/" + orderPublicId + "/payments").headers(authHeaders.buyer(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"method\": \"CARD\" }"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"))
                .andExpect(jsonPath("$.detail").value("OUT_OF_STOCK"));
    }

    @Test
    @DisplayName("재결제: 직전 결제 FAILED·재고 충분 → 201·신규 Payment(Location)")
    void retry_afterFailed_returns201() throws Exception {
        String orderPublicId = performCheckout(null);
        execute("UPDATE payment SET status = 'FAILED' WHERE order_id = "
                + "(SELECT id FROM `order` WHERE public_id = '" + orderPublicId + "')");
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/orders/" + orderPublicId + "/payments").headers(authHeaders.buyer(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"method\": \"CARD\" }"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/payments/pay_")));
    }

    @Test
    @DisplayName("멱등성+D-66: 상품 미존재(404) 후 동일 키 재시도 → 201 — row 삭제 후 신규 처리")
    void checkout_itemNotFound_sameKeyRetryable() throws Exception {
        String key = "idem-key-notfound-001";
        String notFoundBody = """
                {
                  "items": [ { "productId": "prd_XXXXXXXXXXXXXXXXXXXXXXXXXXXX", "variantId": "%s", "quantity": 2 } ],
                  "shippingAddress": {
                    "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                    "zonecode": "06236", "addressRoad": "서울 강남대로 1", "addressDetail": "101호"
                  },
                  "method": "CARD"
                }
                """.formatted(VARIANT_PID);

        // 1차: 상품 미존재 → 404 (D-66 fix: 4xx → IN_PROGRESS row 삭제)
        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(notFoundBody))
                .andExpect(status().isNotFound());
        entityManager.flush();
        entityManager.clear();

        // 2차: 동일 키 + 올바른 상품 → row 삭제됨 → 신규 처리 → 201
        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("멱등성+D-66: variant 불일치(422) 후 동일 키 재시도 → 201 — row 삭제 후 신규 처리")
    void checkout_itemMismatch_sameKeyRetryable() throws Exception {
        String key = "idem-key-mismatch-001";
        String wrongVariantPid = "var_0000000000000000000000IT02";
        // product_id=9999는 PRODUCT_PID(DB id=1000)와 불일치 → CheckoutItemMismatchException(422) 유발
        execute("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                + "VALUES (1001, '" + wrongVariantPid + "', 9999, 'WRONG', 0, 'SALE', 0, 2, 1, NOW(6), NOW(6))");
        entityManager.flush();

        String mismatchBody = """
                {
                  "items": [ { "productId": "%s", "variantId": "%s", "quantity": 2 } ],
                  "shippingAddress": {
                    "recipientName": "홍길동", "recipientPhone": "010-1234-5678",
                    "zonecode": "06236", "addressRoad": "서울 강남대로 1", "addressDetail": "101호"
                  },
                  "method": "CARD"
                }
                """.formatted(PRODUCT_PID, wrongVariantPid);

        // 1차: variant 불일치 → 422 (D-66 fix: 4xx → IN_PROGRESS row 삭제)
        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(mismatchBody))
                .andExpect(status().isUnprocessableEntity());
        entityManager.flush();
        entityManager.clear();

        // 2차: 동일 키 + 올바른 variant → row 삭제됨 → 신규 처리 → 201
        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("멱등성+D-66: 5xx 후 동일 키 재시도 → 409 유지 — row 잔류(회귀 방어)")
    void checkout_5xx_sameKeyBlocked() throws Exception {
        String key = "idem-key-5xx-001";
        // D-66: 5xx(RuntimeException) → row 잔류 — 4xx 삭제 fix가 5xx에 영향을 주지 않는지 회귀 검증
        Mockito.doThrow(new RuntimeException("5xx 시뮬레이션 — 예상치 못한 서버 오류"))
                .when(orderService).createOrder(any());

        // 1차: 예상치 못한 오류 → 500 (row 잔류)
        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isInternalServerError());
        entityManager.flush();
        entityManager.clear();

        // 2차: 동일 키 → IN_PROGRESS row 잔류 → 409
        mockMvc.perform(post("/api/v1/orders").headers(authHeaders.buyer(1))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_PROGRESS"));
    }

    private String performCheckout(String idempotencyKey) throws Exception {
        var request = post("/api/v1/orders").headers(authHeaders.buyer(1))
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        String location = mockMvc.perform(request).andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        entityManager.flush();
        entityManager.clear();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void failPaymentAndDepleteStock(String orderPublicId) {
        execute("UPDATE payment SET status = 'FAILED' WHERE order_id = "
                + "(SELECT id FROM `order` WHERE public_id = '" + orderPublicId + "')");
        execute("UPDATE inventory SET quantity_available = 0 WHERE variant_id = " + VARIANT_ID);
        entityManager.flush();
        entityManager.clear();
    }

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }
}

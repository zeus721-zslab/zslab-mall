package com.zslab.mall.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.notification.service.NotificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Track 16 D-100 Q17 α′·종료조건 #6·#7 검증 통합 테스트.
 *
 * <p><b>의도</b>: 이벤트 처리 실패 유발 후 {@code zslab.event.failed} 카운터 증가 메트릭 계약(#6)과 {@code /actuator/prometheus}
 * scrape 응답 본문에 {@code zslab.event.*} 노출(#7)을 단언한다. 검증 이벤트 종류(OrderPlaced)·핸들러 구현·테스트 클래스명은
 * 변경 가능(Q17 α′ 박제·메트릭 계약만 고정).
 *
 * <p><b>{@code @AutoConfigureObservability}</b>: {@code @SpringBootTest}는 테스트 오염 방지를 위해 메트릭 export
 * 레지스트리를 기본 비활성화(Simple 폴백만)하므로 PrometheusMeterRegistry·{@code /actuator/prometheus} 엔드포인트가
 * 생성되지 않는다. 본 어노테이션으로 실제 export 자동구성을 활성화해 #7 scrape 엔드포인트를 검증한다.
 *
 * <p><b>예외 유발 메커니즘(α 강제)</b>: {@link NotificationService}의 {@code record*} 메서드는 본문을 자체 try/catch로 감싸
 * 모든 {@code RuntimeException}을 내부 swallow하므로, 의존 mock(β)·invalid 데이터(γ)로는 핸들러 catch에 예외가 도달하지 않는다.
 * 따라서 {@code @MockitoBean NotificationService} + {@code doThrow}로 빈 전체를 대체해야만 예외가
 * {@code NotificationOrderPlacedHandler} catch까지 전파돼 {@code recordFailed("OrderPlaced")}가 발화한다. 원 흐름(주문 생성)은
 * catch swallow로 비차단(201).
 *
 * <p><b>5중 의무(D-100 Q8 β·D-90 Q5·D-91·LT-02)</b>: NO {@code @Transactional} + {@link TransactionTemplate} + LT-02
 * try-finally(FOREIGN_KEY_CHECKS) + D-91 FK 부모 그래프 시드. AFTER_COMMIT 핸들러는 커밋 후에만 발화하므로 클래스 트랜잭션을
 * 두지 않고, read 대상(user·seller·product·variant·inventory)을 TransactionTemplate으로 커밋 시드한다. HTTP 체크아웃이
 * order·order_item·payment를 실제 커밋 경로로 생성한다(CorrelationIdIntegrationTest 패턴 1:1).
 *
 * <p><b>카운터 delta 단언</b>: MeterRegistry는 컨텍스트 캐싱으로 테스트 간 카운터가 누적되므로 절대값(==1.0) 대신 호출 전후
 * 차이(after-before==1.0)로 단언한다(라이브 트랩 회피·기조 2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class EventFailedMetricIntegrationTest {

    private static final long USER_ID = 9402L;
    private static final long SELLER_ID = 9402L;
    private static final long PRODUCT_ID = 9402L;
    private static final long VARIANT_ID = 9402L;
    private static final long INVENTORY_ID = 9402L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회·해당 테이블 INSERT 없음). */
    private static final long DUMMY_FK_ID = 9402L;

    private static final long BASE_PRICE = 8_000L;
    private static final long ADDITIONAL_PRICE = 2_000L;

    private static final String EVENT_NAME = "OrderPlaced";

    private static final String SELLER_PID = pid("slr_", "T16FAIL");
    private static final String PRODUCT_PID = pid("prd_", "T16FAIL");
    private static final String VARIANT_PID = pid("var_", "T16FAIL");

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

    @MockitoBean
    private NotificationService notificationService;
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedGraph();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("POST /api/v1/orders → 핸들러 예외 유발 → zslab.event.failed{OrderPlaced} 1 증가 + /actuator/prometheus 노출(#6·#7)")
    void orderPlaced_handlerFailure_incrementsFailedCounter_andExposesScrape() throws Exception {
        doThrow(new RuntimeException("적재 실패 유발")).when(notificationService).recordOrderPlaced(any());

        double failedBefore = counterCount("zslab.event.failed", EVENT_NAME);
        double publishedBefore = counterCount("zslab.event.published", EVENT_NAME);

        // 핸들러 catch가 예외를 swallow하므로 원 흐름(주문 생성)은 201로 정상 종료.
        mockMvc.perform(post("/api/v1/orders")
                        .headers(authHeaders.buyer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

        // 종료조건 #6: 이벤트 처리 실패 유발 후 failed 카운터 증가(메트릭 계약).
        assertThat(counterCount("zslab.event.failed", EVENT_NAME) - failedBefore)
                .as("zslab.event.failed{event=OrderPlaced} 증가량").isEqualTo(1.0);
        // published 연동 동작(TracedEventPublisher.recordPublished·발행 시점 계측).
        assertThat(counterCount("zslab.event.published", EVENT_NAME) - publishedBefore)
                .as("zslab.event.published{event=OrderPlaced} 증가량").isEqualTo(1.0);

        // 종료조건 #7: /actuator/prometheus scrape 응답에 메트릭 노출(Micrometer가 '.'을 '_'로 변환·카운터는 '_total' 접미사).
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("zslab_event_published")))
                .andExpect(content().string(containsString("zslab_event_failed")));
    }

    private double counterCount(String name, String eventName) {
        Counter counter = meterRegistry.find(name).tag("event", eventName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // ---------- seed·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** read 대상 부모 그래프(user·seller·product·variant·inventory)를 FK 비활성 상태로 커밋 시드한다(D-91·LT-02). */
    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T16FAIL"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙16셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, SELLER_PID);
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙16상품', 'SALE', ?, NOW(6), NOW(6))",
                        PRODUCT_ID, PRODUCT_PID, SELLER_ID, DUMMY_FK_ID, BASE_PRICE);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCT16', ?, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, VARIANT_PID, PRODUCT_ID, ADDITIONAL_PRICE, DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, "
                                + "quantity_available, created_at, updated_at) VALUES (?, ?, 100, 0, 100, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE p FROM payment p JOIN `order` o ON p.order_id = o.id WHERE o.buyer_id = ?", USER_ID);
                jdbc.update("DELETE oi FROM order_item oi JOIN `order` o ON oi.order_id = o.id WHERE o.buyer_id = ?", USER_ID);
                jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", USER_ID);
                jdbc.update("DELETE FROM order_idempotency_key WHERE buyer_id = ?", USER_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

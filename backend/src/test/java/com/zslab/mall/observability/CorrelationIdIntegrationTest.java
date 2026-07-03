package com.zslab.mall.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.web.TraceIdFilter;
import com.zslab.mall.order.event.OrderPlaced;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.RecordApplicationEvents;
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Track 16 D-100 Q16 α·종료조건 #2 검증 통합 테스트.
 *
 * <p><b>의도</b>: 대표 이벤트 흐름(HTTP → publish → AFTER_COMMIT → 로그)에 대해 관측성 컨텍스트 MDC가 핸들러
 * 진입까지 누락 없이 전파되는지 실측한다. 핵심 단언은 종료조건 #2 "correlationId 누락 없음"이며, traceId·correlationId가
 * TraceIdFilter(요청 기원·D-48·Q13 α)에서 주입돼 OrderPlaced AFTER_COMMIT 핸들러까지 자연 상속됨을 검증한다.
 *
 * <p><b>스코프</b>: 단일 대표 endpoint(POST /api/v1/orders) 1 시나리오. 운영 지속 측정은 외부 로그 수집 인프라 도입
 * 시점 자연 진입(Q16 α 박제·"수치 집착 제거"·LogbackAppender 추가는 과잉개발 회피). 본 테스트는 PR-1 종결 기준 단독 충족.
 * eventName MDC 단언은 D-100 Q3 β′ 옵션 4 채택(2026-06-30·TracedEventPublisher 책임 미보유)으로 제거.
 * eventName 운영 로그 출력은 핸들러 catch 6 표준키 event 직접 인용으로 충족.
 *
 * <p><b>5중 의무(D-100 Q8 β·D-90 Q5·D-91·LT-02)</b>: NO {@code @Transactional} + {@link TransactionTemplate} +
 * {@code @RecordApplicationEvents} + LT-02 try-finally(FOREIGN_KEY_CHECKS) + D-91 FK 부모 그래프 시드. AFTER_COMMIT
 * 핸들러는 커밋 후에만 발화하므로 클래스 트랜잭션을 두지 않고, read 대상(user·seller·product·variant·inventory)을
 * TransactionTemplate으로 커밋 시드한다. HTTP 체크아웃이 order·order_item·payment를 실제 커밋 경로로 생성한다.
 *
 * <p><b>MDC 캡처(MdcPropagationTest 패턴 1:1 재사용)</b>: {@link OrderPlacedMdcCaptor}를 {@code @TestConfiguration}에
 * 보조 {@code @TransactionalEventListener(AFTER_COMMIT)}로 등록해 OrderPlaced 핸들러 진입 시점 MDC 스냅샷을 캡처한다.
 * 운영 핸들러(NotificationOrderPlacedHandler)와 동일 스레드·동일 단계로 발화하므로 운영 핸들러가 관측하는 MDC와 동일하다.
 *
 * <p><b>traceId·correlationId 보존 근거</b>: traceId·correlationId는 TraceIdFilter가 요청 전체를 감싸는 finally에서만
 * 제거하므로 커밋·AFTER_COMMIT이 그 안에서 일어나 핸들러까지 보존된다. eventName은 옵션 4 채택으로 어떤 컴포넌트도
 * MDC에 주입하지 않으므로(TracedEventPublisher 책임 미보유) 본 테스트는 traceId·correlationId 2종만 단언한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class CorrelationIdIntegrationTest {

    private static final long USER_ID = 9401L;
    private static final long SELLER_ID = 9401L;
    private static final long PRODUCT_ID = 9401L;
    private static final long VARIANT_ID = 9401L;
    private static final long INVENTORY_ID = 9401L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회·해당 테이블 INSERT 없음). */
    private static final long DUMMY_FK_ID = 9401L;

    private static final long BASE_PRICE = 8_000L;
    private static final long ADDITIONAL_PRICE = 2_000L;

    private static final String SELLER_PID = pid("slr_", "T16OBS");
    private static final String PRODUCT_PID = pid("prd_", "T16OBS");
    private static final String VARIANT_PID = pid("var_", "T16OBS");

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private OrderPlacedMdcCaptor captor;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        captor.reset();
        seedGraph();
    }

    @AfterEach
    void tearDown() {
        cleanup();
        MDC.clear();
    }

    @Test
    @DisplayName("POST /api/v1/orders → OrderPlaced AFTER_COMMIT 핸들러까지 correlationId·traceId 누락 없음(종료조건 #2)")
    void httpCheckout_propagatesCorrelationIdToAfterCommitHandler() throws Exception {
        String traceIdHeader = mockMvc.perform(post("/api/v1/orders")
                        .headers(authHeaders.buyer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn().getResponse().getHeader("X-Trace-Id");

        // AFTER_COMMIT 핸들러는 요청 스레드에서 동기 발화하므로 perform 반환 시점에 이미 캡처 완료.
        assertThat(captor.fired).as("OrderPlaced AFTER_COMMIT 핸들러 발화").isTrue();
        // 종료조건 #2 핵심: correlationId 누락 없음·요청 기원 traceId와 동일 값(Q13 α).
        assertThat(captor.traceId).as("핸들러 진입 traceId").isEqualTo(traceIdHeader);
        assertThat(captor.traceId).as("traceId ULID 26자").hasSize(26);
        assertThat(captor.correlationId).as("핸들러 진입 correlationId == traceId").isEqualTo(traceIdHeader);
        // 로그 흐름 종단: NotificationOrderPlacedHandler가 동일 단계에서 NotificationLog 1건 적재.
        assertThat(notificationLogCount()).as("ORDER 알림 적재 1건").isEqualTo(1);
    }

    // ---------- seed·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** read 대상 부모 그래프(user·seller·product·variant·inventory)를 FK 비활성 상태로 커밋 시드한다(D-91·LT-02). */
    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T16OBS"));
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

    private int notificationLogCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE target_type = 'ORDER' AND recipient_user_id = ?",
                Integer.class, USER_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    /**
     * OrderPlaced AFTER_COMMIT 핸들러 진입 시점 MDC traceId·correlationId 스냅샷 캡처 보조 빈(운영 핸들러와 동일 단계·동일 스레드 발화).
     * 단일 스레드 동기 발화이므로 volatile로 충분하다(MdcPropagationTest TestOuterEventHandler 패턴 준용).
     */
    static class OrderPlacedMdcCaptor {

        private volatile boolean fired;
        private volatile String traceId;
        private volatile String correlationId;

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onOrderPlaced(OrderPlaced event) {
            this.traceId = MDC.get(TraceIdFilter.TRACE_ID);
            this.correlationId = MDC.get(TraceIdFilter.CORRELATION_ID);
            this.fired = true;
        }

        void reset() {
            this.fired = false;
            this.traceId = null;
            this.correlationId = null;
        }
    }

    @TestConfiguration
    static class CaptorConfig {

        @Bean
        OrderPlacedMdcCaptor orderPlacedMdcCaptor() {
            return new OrderPlacedMdcCaptor();
        }
    }
}

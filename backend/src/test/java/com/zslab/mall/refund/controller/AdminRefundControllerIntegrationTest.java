package com.zslab.mall.refund.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.payment.gateway.MockRefundResponse;
import com.zslab.mall.payment.gateway.PaymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import com.zslab.mall.common.security.AuthHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Admin Refund endpoint E2E 통합 테스트(Track 22·D-106·실 MariaDB). HTTP → {@code AdminRefundController} →
 * {@code RefundService.initiateByAdmin} → {@code initiate} → DB 흐름을 실 커밋·HTTP 경유로 실측한다(라이브 트랩 차단·
 * {@link com.zslab.mall.inventory.controller.AdminInventoryControllerIntegrationTest} 패턴 1:1). 운영자 수동 환불 개시 1 endpoint를 커버한다.
 *
 * <p><b>Admin 책임 경계(D-93 Q3·Q5)</b>: 인증 헤더 검증(401)·성공(200 PENDING)·claimPublicId 미존재(404)·CLM-3 위반(422)
 * 4건을 보장한다(CLAUDE.md 신규 도메인 통합 테스트 3건 의무 초과 충족).
 *
 * <p><b>이벤트 0(D-106 §6)</b>: initiate 경로는 {@code RefundCompleted}를 발행하지 않는다(markCompleted 전용·Refund L137).
 * {@link RecordApplicationEvents}로 refund 패키지 도메인 이벤트 0건을 단언한다(LT-05 비해당·형제 핸들러 순서 무관 회귀 잠금).
 *
 * <p><b>PG 게이트웨이</b>: {@link PaymentGateway}를 {@link MockitoBean}으로 대체해 T2 성공을 결정적으로 주입한다
 * ({@code RefundAutoTriggerIntegrationTest} 패턴). 시드 그래프는 initiate의 claim→order_item→order→PAID payment 해소를 충족한다.
 *
 * <p><b>트랜잭션</b>: {@code initiate} 커밋을 JdbcTemplate 직접 조회로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다.
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)로 한다.
 *
 * <p><b>HTTP 경유 의무</b>: primitive를 직접 호출하지 않고 MockMvc로 endpoint를 구동한다. X-Admin-Id 헤더 stub은
 * {@code HeaderAdminActorResolver}가 해소하되 식별자는 사용하지 않는다(D-93 Q3·헤더 존재·형식 검증만).
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AdminRefundControllerIntegrationTest {

    private static final long ADMIN = 7201L; // Admin 액터 stub(전체 접근·검증 비대상)

    private static final long USER_ID = 9220L;
    private static final long SELLER_ID = 9220L;
    private static final long PRODUCT_ID = 9220L;
    private static final long VARIANT_ID = 9220L;
    private static final long ORDER_ID = 9220L;
    private static final long ORDER_ITEM_ID = 9220L;
    private static final long PAYMENT_ID = 9220L;
    private static final long CLAIM_ID = 9220L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9220L;
    private static final long FULL_AMOUNT = 10_000L;

    private static final String CLAIM_PID = pid("clm_", "ADRCLM");
    private static final String ORDER_ITEM_PID = pid("oit_", "ADROIT");
    private static final String MISSING_CLAIM_PID = pid("clm_", "ADRNON");
    private static final String PG_REFUND_ID = "mock_rfn_track22admin000000001";

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
    private PaymentGateway paymentGateway;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationEvents events;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 인증 실패: X-Admin-Id 헤더 부재 → 401 UNAUTHENTICATED·refund 도메인 이벤트 0")
    void initiateRefund_missingAdminHeader_returns401() throws Exception {
        // resolve()가 claim 조회 이전 최선두에서 throw하므로 시드 불요(claimPublicId 미존재여도 401이 우선한다).
        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PID + "/initiate-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FULL_AMOUNT)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(refundDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T2 성공: 유효 X-Admin-Id + APPROVED Claim + 유효 amount → 200·PENDING·pgRefundId 부여·Refund 1행 커밋·이벤트 0")
    void initiateRefund_validAdmin_approvedClaim_returns200_persistsPending() throws Exception {
        seedGraph(ClaimStatus.APPROVED);
        when(paymentGateway.refund(any(), any())).thenReturn(new MockRefundResponse(PG_REFUND_ID, true, null));

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PID + "/initiate-refund")
                        .headers(AuthHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FULL_AMOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundPublicId").value(startsWith("rfn_")))
                .andExpect(jsonPath("$.claimPublicId").value(CLAIM_PID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.pgRefundId").value(PG_REFUND_ID));

        // 커밋된 DB 상태 검증(Refund PENDING 1행·amount·pg_refund_id 부여)
        assertThat(refundCount()).isEqualTo(1);
        assertThat(refundStatus()).isEqualTo("PENDING");
        assertThat(refundAmount()).isEqualTo(FULL_AMOUNT);
        assertThat(refundPgRefundId()).isEqualTo(PG_REFUND_ID);
        // D-106 §6: initiate 경로는 RefundCompleted 미발행(이벤트 0·LT-05 비해당).
        assertThat(refundDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T3 실패: 미존재 claimPublicId → 404 CLAIM_NOT_FOUND·이벤트 0")
    void initiateRefund_unknownClaimPublicId_returns404() throws Exception {
        // 시드 없음(claim 미존재). resolve 통과 후 findByPublicId 실패 → ClaimNotFoundException 404.
        mockMvc.perform(post("/api/v1/admin/claims/" + MISSING_CLAIM_PID + "/initiate-refund")
                        .headers(AuthHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FULL_AMOUNT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));

        assertThat(refundDomainEventCount()).isZero();
    }

    @Test
    @DisplayName("T4 실패: APPROVED 아닌 Claim(REQUESTED) → 422 CLAIM_STATE_INVALID·Refund 0행 롤백·이벤트 0")
    void initiateRefund_claimNotApproved_returns422_noRefund() throws Exception {
        seedGraph(ClaimStatus.REQUESTED);

        mockMvc.perform(post("/api/v1/admin/claims/" + CLAIM_PID + "/initiate-refund")
                        .headers(AuthHeaders.admin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FULL_AMOUNT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        // CLM-3 위반은 Refund.create 이전(initiate 상태 게이트)에서 throw → refund 행 미생성·롤백.
        assertThat(refundCount()).isZero();
        assertThat(refundDomainEventCount()).isZero();
    }

    // ---------- seed·helpers (RefundAutoTriggerIntegrationTest 시드 그래프 1:1·AdminInventoryControllerIntegrationTest 스켈레톤) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** FK 부모 그래프(user·seller·product·variant·order·order_item·PAID payment·claim)를 FK 비활성 상태로 시드하고 복원한다(LT-02). */
    private void seedGraph(ClaimStatus claimStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "ADRUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙22셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "ADRSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙22상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "ADRPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCADR', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "ADRVAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "ADRORD"), USER_ID, "ORDADR" + ORDER_ID, FULL_AMOUNT);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                        ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                        FULL_AMOUNT, FULL_AMOUNT, OrderItemStatus.PAID.name());
                jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, "
                                + "payment_attempt_key, paid_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_track22_admin_0001', "
                                + "'pat_track22_admin_0001', NOW(6), NOW(6), NOW(6))",
                        PAYMENT_ID, pid("pay_", "ADRPAY"), ORDER_ID, FULL_AMOUNT);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, "
                                + "previous_order_item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'CANCEL', 'CHANGE_MIND', ?, 'PAID', NOW(6), NOW(6))",
                        CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, claimStatus.name());
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM refund WHERE claim_id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String body(long amount) {
        return "{\"amount\":" + amount + "}";
    }

    /** refund 패키지에서 발행된 도메인 이벤트 수(0이어야 한다·initiate 경로 RefundCompleted 미발행·D-106 §6). */
    private long refundDomainEventCount() {
        return events.stream()
                .filter(event -> event.getClass().getPackageName().startsWith("com.zslab.mall.refund"))
                .count();
    }

    private int refundCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, CLAIM_ID);
        return count == null ? 0 : count;
    }

    private String refundStatus() {
        return jdbc.queryForObject("SELECT status FROM refund WHERE claim_id = ?", String.class, CLAIM_ID);
    }

    private long refundAmount() {
        return jdbc.queryForObject("SELECT amount FROM refund WHERE claim_id = ?", Long.class, CLAIM_ID);
    }

    private String refundPgRefundId() {
        return jdbc.queryForObject("SELECT pg_refund_id FROM refund WHERE claim_id = ?", String.class, CLAIM_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

package com.zslab.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Admin Payment mark-cancelled endpoint E2E 통합 테스트(Track 28·D-113·실 MariaDB). HTTP → {@code AdminPaymentController} →
 * {@code PaymentService.markCancelledByAdmin} → {@code markCancelled} → DB 흐름을 실 커밋·HTTP 경유로 실측한다
 * (라이브 트랩 차단·{@link com.zslab.mall.refund.controller.AdminRefundController} 통합 테스트 패턴 1:1). 운영자 수동 결제 취소 1 endpoint를 커버한다.
 *
 * <p><b>Admin 책임 경계(D-93 Q3·Q5)</b>: 인증 헤더 누락(401)·형식 오류(400)·paymentPublicId 미존재(404)·전액환불 취소 성공(200
 * CANCELLED)·CANCELLED 멱등 NO-OP(200 유지)·전액환불 미충족 NO-OP(200 PAID 유지) 6건을 보장한다(CLAUDE.md 신규 도메인 3건 의무 초과 충족).
 *
 * <p><b>시드 최소화(D-113 경로 정합)</b>: markCancelledByAdmin은 payment 조회 → refund COMPLETED 누적 합(SELECT) → payment.cancel()
 * UPDATE만 접근하며 order/claim 그래프를 읽지 않는다(Payment는 order에 @ManyToOne 없음·orderId plain Long). 따라서 payment + refund
 * 2행만 시드한다. payment.order_id·refund.claim_id의 FK 부모는 없으나(FK_CHECKS=0 시드), status UPDATE는 order_id(updatable=false)를
 * 건드리지 않아 FK 재검증이 없으므로 테스트 본체는 FK 활성 상태로 안전하다(INSERT/DELETE 없음).
 *
 * <p><b>트랜잭션</b>: markCancelledByAdmin 커밋을 JdbcTemplate 직접 조회로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다.
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally 복원)로 한다.
 *
 * <p><b>HTTP 경유 의무</b>: primitive를 직접 호출하지 않고 MockMvc로 endpoint를 구동한다. X-Admin-Id 헤더 stub은
 * {@code HeaderAdminActorResolver}가 해소하되 식별자는 사용하지 않는다(D-93 Q3·헤더 존재·형식 검증만).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPaymentControllerIntegrationTest {

    private static final String ADMIN_ID_HEADER = "X-Admin-Id";
    private static final long ADMIN = 8201L; // Admin 액터 stub(전체 접근·검증 비대상)

    private static final long PAYMENT_ID = 8201L;
    private static final long REFUND_ID = 8201L;
    /** payment.order_id·refund.claim_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회·본 경로 미조회). */
    private static final long ORDER_ID = 8201L;
    private static final long CLAIM_ID = 8201L;
    private static final long FULL_AMOUNT = 10_000L;
    private static final long PARTIAL_AMOUNT = 3_000L;

    private static final String PAYMENT_PID = pid("pay_", "ADPPAY");
    private static final String MISSING_PAYMENT_PID = pid("pay_", "ADPNON");
    private static final String REFUND_PID = pid("rfn_", "ADPRFN");
    private static final String PG_REFUND_ID = "mock_rfn_track28admin000000001";

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
    @DisplayName("T1 인증 실패: X-Admin-Id 헤더 부재 → 401 UNAUTHENTICATED")
    void markCancelled_missingAdminHeader_returns401() throws Exception {
        // resolve()가 payment 조회 이전 최선두에서 throw하므로 시드 불요(paymentPublicId 미존재여도 401이 우선한다).
        mockMvc.perform(post(endpoint(PAYMENT_PID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("T2 인증 실패: X-Admin-Id 형식 오류(비정수) → 400 MALFORMED_REQUEST")
    void markCancelled_malformedAdminHeader_returns400() throws Exception {
        mockMvc.perform(post(endpoint(PAYMENT_PID)).header(ADMIN_ID_HEADER, "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("T3 실패: 미존재 paymentPublicId → 404 PAYMENT_NOT_FOUND")
    void markCancelled_unknownPaymentPublicId_returns404() throws Exception {
        // 시드 없음(payment 미존재). resolve 통과 후 findByPublicId 실패 → PaymentNotFoundException 404.
        mockMvc.perform(post(endpoint(MISSING_PAYMENT_PID)).header(ADMIN_ID_HEADER, String.valueOf(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("T4 성공: PAID + 전액 refund COMPLETED → 200·status=CANCELLED·DB CANCELLED 커밋")
    void markCancelled_paidWithFullRefund_returns200_cancelled() throws Exception {
        seedPayment("PAID");
        seedRefund("COMPLETED", FULL_AMOUNT);

        mockMvc.perform(post(endpoint(PAYMENT_PID)).header(ADMIN_ID_HEADER, String.valueOf(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentPublicId").value(PAYMENT_PID))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 커밋된 DB 상태 검증(PAID→CANCELLED 전이 반영).
        assertThat(paymentStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("T5 멱등: 이미 CANCELLED → 200 NO-OP·status=CANCELLED 유지(L198 가드)")
    void markCancelled_alreadyCancelled_returns200_idempotent() throws Exception {
        seedPayment("CANCELLED");

        mockMvc.perform(post(endpoint(PAYMENT_PID)).header(ADMIN_ID_HEADER, String.valueOf(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(paymentStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("T6 NO-OP: PAID + 부분 refund(전액 미충족) → 200·status=PAID 유지(L202 D-71 가드)")
    void markCancelled_paidWithPartialRefund_returns200_noOp() throws Exception {
        seedPayment("PAID");
        seedRefund("COMPLETED", PARTIAL_AMOUNT);

        mockMvc.perform(post(endpoint(PAYMENT_PID)).header(ADMIN_ID_HEADER, String.valueOf(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(paymentStatus()).isEqualTo("PAID");
    }

    // ---------- seed·helpers (AdminRefundControllerIntegrationTest 스켈레톤·payment+refund 최소 시드) ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(status·amount 포함 전 변수 바인딩·문자열 concat 없음·SQL injection 위험 없음).

    private static String endpoint(String paymentPublicId) {
        return "/api/v1/admin/payments/" + paymentPublicId + "/mark-cancelled";
    }

    /** 고정 id로 payment 1행을 시드한다(FK 비활성·order 부모 생략·LT-02 복원). amount는 FULL_AMOUNT 고정. */
    private void seedPayment(String status) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, "
                                + "payment_attempt_key, paid_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'CARD', ?, ?, 'MOCK_PG', 'tid_track28_admin_0001', "
                                + "'pat_track28_admin_0001', NOW(6), NOW(6), NOW(6))",
                        PAYMENT_ID, PAYMENT_PID, ORDER_ID, FULL_AMOUNT, status);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /** 고정 id로 refund 1행을 시드한다(FK 비활성·claim 부모 생략·payment_id는 seedPayment 행 참조). */
    private void seedRefund(String status, long amount) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, refunded_at, "
                                + "pg_refund_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, NOW(6), ?, NOW(6), NOW(6))",
                        REFUND_ID, REFUND_PID, CLAIM_ID, PAYMENT_ID, amount, status, PG_REFUND_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM refund WHERE payment_id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

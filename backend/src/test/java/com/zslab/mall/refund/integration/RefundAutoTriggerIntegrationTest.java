package com.zslab.mall.refund.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.payment.gateway.MockRefundResponse;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.gateway.PaymentGatewayException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ClaimApproved → Refund 자동 트리거 E2E 통합 테스트(Track 11·D-94·실 MariaDB·Flyway V1~V5). ClaimApproved를 커밋
 * 트랜잭션에서 발행 → {@code @TransactionalEventListener(AFTER_COMMIT)} {@code ClaimApprovedHandler} → REQUIRES_NEW에서
 * {@code RefundService.initiate} → Refund PENDING 생성까지 실제 커밋 경로로 검증한다(forward 트리거 결손 종결·D-94 Q1·Q6·Q7·Q8).
 *
 * <p><b>트랜잭션(ClaimEventIntegrationTest 패턴)</b>: AFTER_COMMIT 핸들러는 커밋 후에만 실행되므로 클래스에 {@code @Transactional}을
 * 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}으로 커밋하고, 검증은 {@link JdbcTemplate}
 * 직접 조회로 한다. 발행도 {@link TransactionTemplate} 안에서 수행해 그 커밋 시점에 핸들러가 동기적으로 발화한다(@Async 아님).
 *
 * <p><b>FK 정합(D-91·WARN-6)</b>: refund INSERT는 claim·payment FK를 재검증하므로 직접 부모(claim·payment)와 그 상위
 * 그래프(user·seller·product·product_variant·order·order_item)를 모두 시드한다. ClaimEventIntegrationTest 패턴 준용.
 *
 * <p><b>LT-02</b>: {@code SET FOREIGN_KEY_CHECKS=0}은 try-finally로 {@code =1} 복원과 1:1 짝을 이룬다.
 *
 * <p><b>PG 게이트웨이</b>: {@link PaymentGateway}를 {@link MockitoBean}으로 대체해 성공/실패를 결정적으로 주입한다. PG 실패는
 * {@code initiate} 내부에서 FAILED로 전이되어(D-67) 핸들러 catch까지 가지 않으므로 I3는 FAILED 행 생성으로 관측한다.
 */
@SpringBootTest
class RefundAutoTriggerIntegrationTest {

    private static final long USER_ID = 9201L;
    private static final long SELLER_ID = 9201L;
    private static final long PRODUCT_ID = 9201L;
    private static final long VARIANT_ID = 9201L;
    private static final long ORDER_ID = 9201L;
    private static final long ORDER_ITEM_ID = 9201L;
    private static final long PAYMENT_ID = 9201L;
    private static final long CLAIM_ID = 9201L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(해당 테이블 UPDATE 없음·FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9201L;
    private static final long FULL_AMOUNT = 10_000L;

    private static final String ORDER_ITEM_PID = pid("oit_", "T11OIT");
    private static final String CLAIM_PID = pid("clm_", "T11CLM");
    private static final String PG_REFUND_ID = "mock_rfn_track11auto0000000001";

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
    private ApplicationEventPublisher eventPublisher;
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
    @DisplayName("I1 자동 트리거: ClaimApproved(CANCEL) 발행 → 핸들러 → Refund PENDING 1건·amount=OrderItem.totalPrice")
    void claimApproved_triggersRefundPending() {
        seedGraph(ClaimStatus.APPROVED, ClaimType.CANCEL);
        when(paymentGateway.refund(any(), any())).thenReturn(new MockRefundResponse(PG_REFUND_ID, true, null));

        publishApproved();

        assertThat(refundCount()).isEqualTo(1);
        assertThat(refundStatus()).isEqualTo("PENDING");
        assertThat(refundAmount()).isEqualTo(FULL_AMOUNT);
    }

    @Test
    @DisplayName("I2 멱등: ClaimApproved 재발행 → 활성 PENDING 존재 → 추가 Refund 미생성(count=1·D-94 Q6)")
    void claimApproved_reDelivered_idempotentNoNewRow() {
        seedGraph(ClaimStatus.APPROVED, ClaimType.CANCEL);
        when(paymentGateway.refund(any(), any())).thenReturn(new MockRefundResponse(PG_REFUND_ID, true, null));

        publishApproved();
        publishApproved(); // 활성 Refund 존재 → Service 멱등 게이트 no-op

        assertThat(refundCount()).isEqualTo(1);
        assertThat(refundStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("I3 PG 실패: gateway.refund 예외 → initiate 내부 FAILED 전이(D-67)·Refund FAILED 1건·예외 미전파")
    void claimApproved_gatewayFailure_refundFailed() {
        seedGraph(ClaimStatus.APPROVED, ClaimType.CANCEL);
        when(paymentGateway.refund(any(), any()))
                .thenThrow(new PaymentGatewayException("k", "PG_DOWN", "환불 게이트웨이 장애"));

        publishApproved(); // 핸들러는 AFTER_COMMIT·REQUIRES_NEW·예외는 initiate가 흡수 → 발행 측에 전파되지 않음

        assertThat(refundCount()).isEqualTo(1);
        assertThat(refundStatus()).isEqualTo("FAILED");
    }

    // ---------- publish ----------

    /** 커밋 트랜잭션 안에서 ClaimApproved를 발행한다 → 커밋 시점에 AFTER_COMMIT 핸들러가 동기 발화한다. */
    private void publishApproved() {
        tx.executeWithoutResult(s -> eventPublisher.publishEvent(new ClaimApproved(
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, ClaimType.CANCEL, ClaimStatus.APPROVED, LocalDateTime.now())));
    }

    // ---------- seed·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** FK 부모 그래프(user·seller·product·variant·order·order_item·payment·claim)를 FK 비활성 상태로 시드하고 복원한다(LT-02). */
    private void seedGraph(ClaimStatus claimStatus, ClaimType claimType) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T11USR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙11셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "T11SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙11상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "T11PRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCT11', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "T11VAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "T11ORD"), USER_ID, "ORDT11" + ORDER_ID, FULL_AMOUNT);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                        ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                        FULL_AMOUNT, FULL_AMOUNT, OrderItemStatus.PAID.name());
                jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, "
                                + "payment_attempt_key, paid_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_track11_auto_0001', "
                                + "'pat_track11_auto_0001', NOW(6), NOW(6), NOW(6))",
                        PAYMENT_ID, pid("pay_", "T11PAY"), ORDER_ID, FULL_AMOUNT);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'CHANGE_MIND', ?, NOW(6), NOW(6))",
                        CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, claimType.name(), claimStatus.name());
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

    private int refundCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, CLAIM_ID);
    }

    private String refundStatus() {
        return jdbc.queryForObject("SELECT status FROM refund WHERE claim_id = ?", String.class, CLAIM_ID);
    }

    private long refundAmount() {
        return jdbc.queryForObject("SELECT amount FROM refund WHERE claim_id = ?", Long.class, CLAIM_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

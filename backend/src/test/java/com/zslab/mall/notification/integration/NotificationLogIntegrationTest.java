package com.zslab.mall.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.payment.event.PaymentCompleted;
import com.zslab.mall.payment.gateway.MockRefundResponse;
import com.zslab.mall.payment.gateway.PaymentGateway;
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
 * 이벤트 → NotificationLog 적재 E2E 통합 테스트(Track 12·D-95·실 MariaDB·Flyway). 발행처 존재 4 이벤트(OrderPlaced·
 * PaymentCompleted·ClaimCompleted·ClaimApproved)를 커밋 트랜잭션에서 발행 → {@code @TransactionalEventListener(AFTER_COMMIT)}
 * 알림 핸들러 → REQUIRES_NEW에서 NotificationLog PENDING 1건 적재까지 실제 커밋 경로로 검증한다.
 *
 * <p><b>트랜잭션(RefundAutoTriggerIntegrationTest 패턴)</b>: AFTER_COMMIT 핸들러는 커밋 후에만 실행되므로 클래스에
 * {@code @Transactional}을 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}으로
 * 커밋하고, 검증은 {@link JdbcTemplate} 직접 조회로 한다. 발행도 {@link TransactionTemplate} 안에서 수행해 그 커밋
 * 시점에 핸들러가 동기적으로 발화한다(@Async 아님).
 *
 * <p><b>FK 정합(D-91)</b>: notification_log는 FK가 없으나(논리참조·NOT-3) 알림 핸들러의 재조회 체인(order·order_item·
 * claim → buyer)과 ClaimApproved 공존 핸들러(refund INSERT의 claim·payment FK 재검증)를 위해 상위 그래프
 * (user·seller·product·product_variant·order·order_item·payment·claim)를 모두 시드한다.
 *
 * <p><b>LT-02</b>: {@code SET FOREIGN_KEY_CHECKS=0}은 try-finally로 {@code =1} 복원과 1:1 짝을 이룬다.
 *
 * <p><b>PG 게이트웨이</b>: T4에서 refund/ClaimApprovedHandler가 공존 발화하므로 {@link PaymentGateway}를
 * {@link MockitoBean}으로 대체해 성공을 결정적으로 주입한다(알림 적재 검증과 무관한 PG 실 호출 차단).
 */
@SpringBootTest
class NotificationLogIntegrationTest {

    private static final long USER_ID = 9301L;
    private static final long SELLER_ID = 9301L;
    private static final long PRODUCT_ID = 9301L;
    private static final long VARIANT_ID = 9301L;
    private static final long ORDER_ID = 9301L;
    private static final long ORDER_ITEM_ID = 9301L;
    private static final long PAYMENT_ID = 9301L;
    private static final long CLAIM_ID = 9302L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9301L;
    private static final long FULL_AMOUNT = 10_000L;

    private static final String ORDER_PID = pid("ord_", "T12ORD");
    private static final String CLAIM_PID = pid("clm_", "T12CLM");
    private static final String PG_REFUND_ID = "mock_rfn_track12notif000000001";

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
    @DisplayName("T1 OrderPlaced 발행 → NotificationLog PENDING 1건·target_type=ORDER·template=TPL_ORDER_PLACED·channel=EMAIL")
    void orderPlaced_recordsNotificationLog() {
        seedGraph(OrderStatus.PAID, OrderItemStatus.PAID, ClaimStatus.APPROVED, ClaimType.CANCEL);

        tx.executeWithoutResult(s -> eventPublisher.publishEvent(
                new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now())));

        assertThat(count("ORDER", ORDER_ID)).isEqualTo(1);
        assertThat(value("template_code", "ORDER", ORDER_ID)).isEqualTo("TPL_ORDER_PLACED");
        assertThat(value("channel", "ORDER", ORDER_ID)).isEqualTo("EMAIL");
        assertThat(value("status", "ORDER", ORDER_ID)).isEqualTo("PENDING");
        assertThat(recipient("ORDER", ORDER_ID)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("T2 PaymentCompleted 발행 → NotificationLog 1건 + 동기 OrderEventHandler markPaid 공존·Order.status=PAID")
    void paymentCompleted_recordsNotificationLog_andOrderMarkedPaid() {
        seedGraph(OrderStatus.PENDING_PAYMENT, OrderItemStatus.ORDERED, ClaimStatus.APPROVED, ClaimType.CANCEL);

        tx.executeWithoutResult(s -> eventPublisher.publishEvent(
                new PaymentCompleted(PAYMENT_ID, ORDER_ID, FULL_AMOUNT, "tid_t12_pay_0001", LocalDateTime.now())));

        assertThat(count("ORDER", ORDER_ID)).isEqualTo(1);
        assertThat(value("template_code", "ORDER", ORDER_ID)).isEqualTo("TPL_PAYMENT_COMPLETED");
        assertThat(value("status", "ORDER", ORDER_ID)).isEqualTo("PENDING");
        // 동기 OrderEventHandler(markPaid)와 비동기 알림 핸들러 공존 실측
        assertThat(orderStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("T3 ClaimCompleted 발행 → NotificationLog 1건·target_type=CLAIM·template=TPL_CLAIM_COMPLETED")
    void claimCompleted_recordsNotificationLog() {
        seedGraph(OrderStatus.PAID, OrderItemStatus.PAID, ClaimStatus.COMPLETED, ClaimType.CANCEL);

        tx.executeWithoutResult(s -> eventPublisher.publishEvent(new ClaimCompleted(
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, ClaimType.CANCEL, ClaimStatus.COMPLETED, LocalDateTime.now())));

        assertThat(count("CLAIM", CLAIM_ID)).isEqualTo(1);
        assertThat(value("template_code", "CLAIM", CLAIM_ID)).isEqualTo("TPL_CLAIM_COMPLETED");
        assertThat(value("status", "CLAIM", CLAIM_ID)).isEqualTo("PENDING");
        assertThat(recipient("CLAIM", CLAIM_ID)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("T4 ClaimApproved 발행 → NotificationLog 1건·target=CLAIM·TPL_CLAIM_APPROVED·refund 자동 트리거와 공존")
    void claimApproved_recordsNotificationLog_coexistsWithRefund() {
        seedGraph(OrderStatus.PAID, OrderItemStatus.PAID, ClaimStatus.APPROVED, ClaimType.CANCEL);
        when(paymentGateway.refund(any(), any())).thenReturn(new MockRefundResponse(PG_REFUND_ID, true, null));

        tx.executeWithoutResult(s -> eventPublisher.publishEvent(new ClaimApproved(
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, ClaimType.CANCEL, ClaimStatus.APPROVED, LocalDateTime.now())));

        assertThat(count("CLAIM", CLAIM_ID)).isEqualTo(1);
        assertThat(value("template_code", "CLAIM", CLAIM_ID)).isEqualTo("TPL_CLAIM_APPROVED");
        assertThat(value("status", "CLAIM", CLAIM_ID)).isEqualTo("PENDING");
        // refund/ClaimApprovedHandler 공존 발화 → Refund PENDING 1건도 생성
        assertThat(refundCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T5 ClaimApproved 발행 + Claim 사후 삭제 → recipient 산정 실패·NotificationLog 미적재(A1-α·A2-α)")
    void claimApproved_claimMissing_skipsNotificationLog() {
        seedGraph(OrderStatus.PAID, OrderItemStatus.PAID, ClaimStatus.APPROVED, ClaimType.CANCEL);
        deleteClaim();

        tx.executeWithoutResult(s -> eventPublisher.publishEvent(new ClaimApproved(
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, ClaimType.CANCEL, ClaimStatus.APPROVED, LocalDateTime.now())));

        assertThat(count("CLAIM", CLAIM_ID)).isZero();
    }

    // ---------- seed·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedGraph(OrderStatus orderStatus, OrderItemStatus itemStatus,
            ClaimStatus claimStatus, ClaimType claimType) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T12USR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙12셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "T12SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙12상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "T12PRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCT12', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "T12VAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, ORDER_PID, USER_ID, "ORDT12" + ORDER_ID, orderStatus.name(), FULL_AMOUNT);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                        ORDER_ITEM_ID, pid("oit_", "T12OIT"), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                        FULL_AMOUNT, FULL_AMOUNT, itemStatus.name());
                jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, "
                                + "payment_attempt_key, paid_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_track12_0001', "
                                + "'pat_track12_0001', NOW(6), NOW(6), NOW(6))",
                        PAYMENT_ID, pid("pay_", "T12PAY"), ORDER_ID, FULL_AMOUNT);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, "
                                + "previous_order_item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'CHANGE_MIND', ?, 'PAID', NOW(6), NOW(6))",
                        CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, claimType.name(), claimStatus.name());
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void deleteClaim() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE target_id IN (?, ?)", ORDER_ID, CLAIM_ID);
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

    private int count(String targetType, long targetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE target_type = ? AND target_id = ?",
                Integer.class, targetType, targetId);
    }

    private String value(String column, String targetType, long targetId) {
        // column은 테스트 상수 리터럴('template_code'·'channel'·'status')만 전달·외부 입력 아님(SQL injection 위험 없음).
        return jdbc.queryForObject(
                "SELECT " + column + " FROM notification_log WHERE target_type = ? AND target_id = ?",
                String.class, targetType, targetId);
    }

    private long recipient(String targetType, long targetId) {
        return jdbc.queryForObject(
                "SELECT recipient_user_id FROM notification_log WHERE target_type = ? AND target_id = ?",
                Long.class, targetType, targetId);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }

    private int refundCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, CLAIM_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

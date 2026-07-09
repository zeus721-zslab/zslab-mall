package com.zslab.mall.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.event.OrderTerminated;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.payment.service.ExpirePaymentService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 결제 자동 만료 E2E 통합 테스트(Track 25·D-08 M-14·FE-12c 재배선·실 MariaDB·Flyway). {@link ExpirePaymentService#expireOne}을
 * 비-테스트-트랜잭션으로 직접 호출해 만료 종료(Payment PENDING→EXPIRED·Order PENDING_PAYMENT→PAYMENT_EXPIRED)와 커밋 후
 * AFTER_COMMIT {@code InventoryOrderTerminatedHandler} 재고 예약 해제까지의 실 커밋 경로를 검증한다.
 *
 * <p><b>시드 원칙</b>: expires_at은 LocalDateTime 매핑이라 raw JDBC 시드는 JVM tz와 DB 세션 tz 차이로 값이 shift될 수
 * 있다. 따라서 isExpired 판정이 필요한 케이스(T1·T3)는 JPA 저장(Hibernate LocalDateTime 무변환 왕복)으로,
 * status 재검증에서 단락되는 케이스(T2)와 EXPLAIN 계획 확인(T4)은 raw JDBC로 시드한다.
 *
 * <p><b>스케줄러 자동 발화 차단</b>: {@code zslab.payment.expiry.enabled=false}로 {@code @Scheduled} 배치를 끄고
 * {@code expireOne}을 직접 호출해 결정론을 확보한다(프로젝트에 test profile 부재).
 */
@RecordApplicationEvents
@TestPropertySource(properties = "zslab.payment.expiry.enabled=false")
class PaymentExpiryIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryIntegrationTest.class);

    private static final long USER_ID = 9701L;
    private static final long SELLER_ID = 9701L;
    private static final long PRODUCT_ID = 9701L;
    private static final long VARIANT_ID = 9701L;
    private static final long INVENTORY_ID = 9701L;
    private static final long ORDER_ID = 9701L;
    private static final long ORDER_ITEM_ID = 9701L;
    private static final long PAID_PAYMENT_ID = 9702L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9701L;
    private static final int QTY = 2;
    private static final long AMOUNT = 20_000L;

    private static final String ORDER_PID = pid("ord_", "T25ORD");
    private static final String ORDER_ITEM_PID = pid("oit_", "T25OIT");

    @Autowired
    private ExpirePaymentService expirePaymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ApplicationEvents applicationEvents;

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
    @DisplayName("T1 만료 PENDING → expireOne → Payment EXPIRED·Order PAYMENT_EXPIRED 종료 + AFTER_COMMIT 재고 예약 해제")
    void expiredPending_terminates_andReleasesReservation() {
        seedGraph(QTY);
        Long paymentId = seedJpaPendingPayment(LocalDateTime.now().minusMinutes(10));

        // expireOne은 자체 @Transactional — 직접 호출 시 커밋되어 AFTER_COMMIT 핸들러가 동기 발화한다.
        expirePaymentService.expireOne(paymentId);

        assertThat(paymentStatus(paymentId)).isEqualTo("EXPIRED");
        assertThat(orderStatus()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(applicationEvents.stream(OrderTerminated.class).count()).isEqualTo(1);
        assertThat(reserved()).isZero();
        assertThat(available()).isEqualTo(10);
        assertThat(onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("T2 이미 PAID → expireOne skip(재검증 멱등) → 상태 불변·재고 해제 없음")
    void paidPayment_skipsExpiry() {
        seedGraph(QTY);
        tx.executeWithoutResult(s ->
                seedRawPayment(PAID_PAYMENT_ID, "T25PAID", "PAID", LocalDateTime.now().minusMinutes(10)));

        expirePaymentService.expireOne(PAID_PAYMENT_ID);

        assertThat(paymentStatus(PAID_PAYMENT_ID)).isEqualTo("PAID");
        assertThat(applicationEvents.stream(OrderTerminated.class).count()).isZero();
        assertThat(reserved()).isEqualTo(QTY);   // 해제되지 않음
    }

    @Test
    @DisplayName("T3 미만료 PENDING → expireOne skip(!isExpired) → 상태 불변·재고 해제 없음")
    void notExpiredPending_skipsExpiry() {
        seedGraph(QTY);
        Long paymentId = seedJpaPendingPayment(LocalDateTime.now().plusMinutes(30));

        expirePaymentService.expireOne(paymentId);

        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
        assertThat(applicationEvents.stream(OrderTerminated.class).count()).isZero();
        assertThat(reserved()).isEqualTo(QTY);
    }

    @Test
    @DisplayName("T4 만료 배치 조회 EXPLAIN → idx_payment_expire가 후보 인덱스로 적용됨(possible_keys)")
    void expiryQuery_usesExpiryIndex() {
        seedGraph(0);
        tx.executeWithoutResult(s -> {
            seedRawPayment(9711L, "T25P01", "PENDING", LocalDateTime.now().minusMinutes(30));
            seedRawPayment(9712L, "T25P02", "PENDING", LocalDateTime.now().minusMinutes(20));
            seedRawPayment(9713L, "T25P03", "PENDING", LocalDateTime.now().minusMinutes(10));
        });

        // 실행계획 1회 기록(§C FAIL 대응·V8 인덱스 검증). 소량 테이블은 옵티마이저가 full scan을 택할 수 있어
        // 실제 선택(key)이 아닌 후보(possible_keys)로 인덱스 적용 가능성을 검증한다. 모든 변수는 정적 SQL·바인딩 없음.
        List<Map<String, Object>> plan = jdbc.queryForList(
                "EXPLAIN SELECT id FROM payment WHERE status = 'PENDING' AND expires_at < NOW(6) "
                        + "ORDER BY expires_at LIMIT 100");
        log.info("[PaymentExpiry][EXPLAIN] {}", plan);

        String possibleKeys = plan.stream()
                .map(row -> String.valueOf(row.get("possible_keys")))
                .reduce("", (a, b) -> a + b);
        assertThat(possibleKeys).contains("idx_payment_expire");
    }

    // ---------- 시드·helpers ----------

    /** catalog + inventory + order + orderItem 그래프를 시드한다(reserved 지정). */
    private void seedGraph(int reserved) {
        seed(() -> {
            seedCatalog();
            seedInventory(10, reserved, 10 - reserved);
            seedOrder("PENDING_PAYMENT");
            seedOrderItem(OrderItemStatus.ORDERED);
        });
    }

    /** PENDING 결제를 JPA로 저장한다(Hibernate LocalDateTime 무변환 왕복·isExpired tz 안전). 생성 id를 반환. */
    private Long seedJpaPendingPayment(LocalDateTime expiresAt) {
        return tx.execute(status -> paymentRepository.save(
                Payment.create(ORDER_ID, PaymentMethod.CARD, AMOUNT, pid("pat_", "T25PAY"), expiresAt)).getId());
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedRawPayment(long id, String tag, String status, LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, payment_attempt_key, method, amount, status, "
                        + "expires_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'CARD', ?, ?, ?, NOW(6), NOW(6))",
                id, pid("pay_", tag), ORDER_ID, pid("pat_", tag), AMOUNT, status, Timestamp.valueOf(expiresAt));
    }

    /** FK 비활성 상태로 시드하고 복원한다(LT-02 try-finally). */
    private void seed(Runnable seedingWork) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedingWork.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "T25USR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '트랙25셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "T25SLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '트랙25상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "T25PRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCT25', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "T25VAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedInventory(int onHand, int reserved, int available) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                INVENTORY_ID, VARIANT_ID, onHand, reserved, available);
    }

    private void seedOrder(String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 20000, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, ORDER_PID, USER_ID, "ORDT25" + ORDER_ID, status);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 10000, 20000, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, QTY, itemStatus.name());
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM payment WHERE order_id = ?", ORDER_ID);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
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

    private String paymentStatus(long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    private int available() {
        return jdbc.queryForObject("SELECT quantity_available FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

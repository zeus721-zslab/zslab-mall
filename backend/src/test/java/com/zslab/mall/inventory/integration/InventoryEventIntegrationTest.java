package com.zslab.mall.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.payment.event.PaymentCompleted;
import com.zslab.mall.payment.event.PaymentFailed;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Inventory 이벤트 핸들러 E2E 통합 테스트(Track 17 PR-B·D-100 Q8 β·실 MariaDB·Flyway). 이벤트를 커밋 트랜잭션에서 발행해
 * AFTER_COMMIT·REQUIRES_NEW 핸들러(E1 예약·E2 확정·E3 해제·E9 복구/교환)의 실제 커밋 경로를 검증하고 라이브 트랩을 차단한다.
 * 특히 E2는 동기 {@code OrderEventHandler}(markPaid)가 발행 트랜잭션에서 OrderItem을 PAID로 전이한 뒤에도 A′ 설계대로
 * commitReservation이 실행됨을 실증한다(§6 갱신 라이브 트랩 회귀 방지).
 *
 * <p><b>D-100 Q8 β 5중 의무</b>: (1) 클래스 @Transactional 없음 (2) {@link TransactionTemplate} 발행/시드
 * (3) {@link RecordApplicationEvents} 발행 관측 (4) LT-02 try-finally FK_CHECKS 복원 (5) D-91 FK 부모 그래프
 * (user·seller·product·product_variant·inventory·order·order_item·claim) 직접 시드.
 */
@SpringBootTest
@RecordApplicationEvents
class InventoryEventIntegrationTest {

    private static final long USER_ID = 9601L;
    private static final long SELLER_ID = 9601L;
    private static final long PRODUCT_ID = 9601L;
    private static final long VARIANT_ID = 9601L;
    private static final long INVENTORY_ID = 9601L;
    private static final long ORDER_ID = 9601L;
    private static final long ORDER_ITEM_ID = 9601L;
    private static final long CLAIM_ID = 9601L;
    private static final long PAYMENT_ID = 9601L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9601L;
    private static final int QTY = 2;

    private static final String ORDER_PID = pid("ord_", "T17ORD");
    private static final String ORDER_ITEM_PID = pid("oit_", "T17OIT");
    private static final String CLAIM_PID = pid("clm_", "T17CLM");

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
    private ApplicationEventPublisher eventPublisher;
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
    @DisplayName("T1 OrderPlaced(E1) → 각 OrderItem reserve → inventory reserved 증가·available 감소")
    void orderPlaced_reservesInventory() {
        seed(() -> {
            seedCatalog();
            seedInventory(10, 0, 10);
            seedOrder("PENDING_PAYMENT");
            seedOrderItem(OrderItemStatus.ORDERED);
        });

        publishInTx(new OrderPlaced(ORDER_PID, ORDER_ID, LocalDateTime.now()));

        assertThat(reserved()).isEqualTo(QTY);
        assertThat(available()).isEqualTo(10 - QTY);
        assertThat(onHand()).isEqualTo(10);
        assertThat(applicationEvents.stream(OrderPlaced.class).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 PaymentCompleted(E2) → 동기 markPaid(PAID 전이) 후에도 commitReservation 실행 → on_hand·reserved 감소·History ORDER")
    void paymentCompleted_commitsReservation_evenAfterPaid() {
        seed(() -> {
            seedCatalog();
            seedInventory(10, QTY, 10 - QTY);   // E1 예약 완료 상태 가정
            seedOrder("PENDING_PAYMENT");
            seedOrderItem(OrderItemStatus.ORDERED);   // 동기 markPaid가 PENDING_PAYMENT→PAID 전이
        });

        publishInTx(new PaymentCompleted(PAYMENT_ID, ORDER_ID, 20_000L, "pg_tid_t17", LocalDateTime.now()));

        // 발행 TX 내 동기 OrderEventHandler가 PAID로 전이 → AFTER_COMMIT 시점 이미 PAID여도 A′로 차감 진행
        assertThat(itemStatus()).isEqualTo("PAID");
        assertThat(onHand()).isEqualTo(10 - QTY);
        assertThat(reserved()).isZero();
        assertThat(historyCount("order", ORDER_ID, "ORDER")).isEqualTo(1);
    }

    @Test
    @DisplayName("T3 PaymentFailed(E3) → 각 OrderItem release → inventory reserved 감소")
    void paymentFailed_releasesReservation() {
        seed(() -> {
            seedCatalog();
            seedInventory(10, QTY, 10 - QTY);
            seedOrder("PENDING_PAYMENT");
            seedOrderItem(OrderItemStatus.ORDERED);
        });

        publishInTx(new PaymentFailed(PAYMENT_ID, ORDER_ID, "INSUFFICIENT_BALANCE", LocalDateTime.now()));

        assertThat(reserved()).isZero();
        assertThat(available()).isEqualTo(10);
        assertThat(onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("T4-CANCEL ClaimCompleted(E9·CANCEL) → restoreStock → on_hand 증가·History CANCEL(+qty)")
    void claimCompleted_cancel_restoresStock() {
        seed(() -> {
            seedCatalog();
            seedInventory(8, 0, 8);
            seedOrder("PAID");
            seedOrderItem(OrderItemStatus.CANCEL_REQUESTED);
            seedClaim(ClaimType.CANCEL, OrderItemStatus.PAID);
        });

        publishInTx(new ClaimCompleted(CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, ClaimType.CANCEL, ClaimStatus.COMPLETED, LocalDateTime.now()));

        assertThat(onHand()).isEqualTo(8 + QTY);
        assertThat(historyCount("claim", CLAIM_ID, "CANCEL")).isEqualTo(1);
    }

    @Test
    @DisplayName("T4-RETURN ClaimCompleted(E9·RETURN) → restoreStock → on_hand 증가·History RETURN(+qty)")
    void claimCompleted_return_restoresStock() {
        seed(() -> {
            seedCatalog();
            seedInventory(8, 0, 8);
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.RETURN_REQUESTED);
            seedClaim(ClaimType.RETURN, OrderItemStatus.DELIVERED);
        });

        publishInTx(new ClaimCompleted(CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, ClaimType.RETURN, ClaimStatus.COMPLETED, LocalDateTime.now()));

        assertThat(onHand()).isEqualTo(8 + QTY);
        assertThat(historyCount("claim", CLAIM_ID, "RETURN")).isEqualTo(1);
    }

    @Test
    @DisplayName("T4-EXCHANGE ClaimCompleted(E9·EXCHANGE) → exchange(동일 variant) → on_hand 순증감 상쇄·History RETURN+ORDER 2행")
    void claimCompleted_exchange_restoresAndReships() {
        seed(() -> {
            seedCatalog();
            seedInventory(8, 0, 8);
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.EXCHANGE_REQUESTED);
            seedClaim(ClaimType.EXCHANGE, OrderItemStatus.DELIVERED);
        });

        publishInTx(new ClaimCompleted(CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, ClaimType.EXCHANGE, ClaimStatus.COMPLETED, LocalDateTime.now()));

        // 동일 variant 회수(+qty)·재발송(-qty) 상쇄 → on_hand 불변, History 2행
        assertThat(onHand()).isEqualTo(8);
        assertThat(historyCount("claim", CLAIM_ID, "RETURN")).isEqualTo(1);
        assertThat(historyCount("claim", CLAIM_ID, "ORDER")).isEqualTo(1);
    }

    @Test
    @DisplayName("T5 ClaimCompleted 재발행 멱등: 2회 발행 → History('claim') 존재 가드로 복구 1회·on_hand 중복 증가 없음")
    void claimCompleted_redelivery_idempotent() {
        seed(() -> {
            seedCatalog();
            seedInventory(8, 0, 8);
            seedOrder("PAID");
            seedOrderItem(OrderItemStatus.CANCEL_REQUESTED);
            seedClaim(ClaimType.CANCEL, OrderItemStatus.PAID);
        });

        ClaimCompleted event = new ClaimCompleted(CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, ClaimType.CANCEL, ClaimStatus.COMPLETED, LocalDateTime.now());
        publishInTx(event);
        publishInTx(event);   // 2회차: existsByReferenceTypeAndReferenceId("claim", claimId) true → skip

        assertThat(onHand()).isEqualTo(8 + QTY);   // 중복 복구 없음
        assertThat(historyCount("claim", CLAIM_ID, "CANCEL")).isEqualTo(1);
    }

    // ---------- 발행·seed·helpers ----------

    /** 커밋 트랜잭션에서 이벤트를 발행해 그 커밋 시점에 AFTER_COMMIT 핸들러가 동기 발화하도록 한다(@Async 아님). */
    private void publishInTx(Object event) {
        tx.executeWithoutResult(s -> eventPublisher.publishEvent(event));
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

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "T17USR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '트랙17셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "T17SLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '트랙17상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "T17PRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCT17', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "T17VAR"), PRODUCT_ID, DUMMY_FK_ID);
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
                ORDER_ID, ORDER_PID, USER_ID, "ORDT17" + ORDER_ID, status);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 10000, 20000, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, QTY, itemStatus.name());
    }

    private void seedClaim(ClaimType type, OrderItemStatus previousStatus) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, reason_detail, status, "
                        + "previous_order_item_status, requested_by, requested_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'BUYER_CHANGED_MIND', '통합', 'COMPLETED', ?, ?, NOW(6), NOW(6), NOW(6))",
                CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, type.name(), previousStatus.name(), USER_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
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

    private int onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    private int available() {
        return jdbc.queryForObject("SELECT quantity_available FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private int historyCount(String referenceType, Long referenceId, String changeType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_history WHERE reference_type = ? AND reference_id = ? AND change_type = ?",
                Integer.class, referenceType, referenceId, changeType);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

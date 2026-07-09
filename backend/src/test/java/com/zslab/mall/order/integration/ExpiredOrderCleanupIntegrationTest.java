package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.event.OrderTerminated;
import com.zslab.mall.order.service.ExpiredOrderCleanupService;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미결제 종료 주문 hard delete E2E 통합 테스트(FE-12c-2·실 MariaDB·Flyway·{@code OrderAutoCancelIntegrationTest} 구조 미러).
 * {@link ExpiredOrderCleanupService#cleanupOne}을 비-테스트-트랜잭션으로 직접 호출해 실 커밋·삭제·FK RESTRICT·AFTER_COMMIT
 * 재발행 경로를 검증한다.
 *
 * <p>커버: (1) 삭제 성공(reserved==0·자식 순차 삭제) (2) reserved&gt;0 삭제 이연 + OrderTerminated 재발행 + AFTER_COMMIT 재고 해제
 * (3) status!=PAYMENT_EXPIRED skip (4) PENDING payment 존재 skip (5) 손자(delivery) 존재 → FK RESTRICT → 롤백(부분 삭제 없음).
 *
 * <p><b>스케줄러 자동 발화 차단</b>: {@code zslab.order.expired-cleanup.enabled=false}로 {@code @Scheduled} 배치를 끄고
 * {@code cleanupOne}을 직접 호출해 결정론을 확보한다(GRACE_DAYS·updatedAt 유예는 스케줄러 조회 책임·본 테스트 범위 밖).
 */
@RecordApplicationEvents
@TestPropertySource(properties = "zslab.order.expired-cleanup.enabled=false")
class ExpiredOrderCleanupIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9802L;
    private static final long SELLER_ID = 9802L;
    private static final long PRODUCT_ID = 9802L;
    private static final long VARIANT_ID = 9802L;
    private static final long INVENTORY_ID = 9802L;
    private static final long ORDER_ID = 9802L;
    private static final long ORDER_ITEM_ID = 9802L;
    private static final long SNAPSHOT_ID = 9802L;
    private static final long PAYMENT_ID = 9802L;
    private static final long DELIVERY_ID = 9802L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9802L;
    private static final int QTY = 2;

    private static final String ORDER_PID = pid("ord_", "F122ORD");
    private static final String ORDER_ITEM_PID = pid("oit_", "F122OIT");
    private static final String PAYMENT_PID = pid("pay_", "F122PAY");
    private static final String PAYMENT_ATTEMPT_KEY = pid("pat_", "F122PAT");
    private static final String DELIVERY_PID = pid("dlv_", "F122DLV");

    @Autowired
    private ExpiredOrderCleanupService expiredOrderCleanupService;
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
    @DisplayName("T1 PAYMENT_EXPIRED·reserved==0 → 자식(payment·snapshot·order_item)→order 순차 삭제 완료")
    void expiredOrder_reservedZero_deletesGraph() {
        seedGraph("PAYMENT_EXPIRED", OrderItemStatus.ORDERED, 0);
        seedSnapshot();
        seedPayment("EXPIRED");

        expiredOrderCleanupService.cleanupOne(ORDER_ID);

        assertThat(orderExists()).isFalse();
        assertThat(orderItemCount()).isZero();
        assertThat(snapshotCount()).isZero();
        assertThat(paymentCount()).isZero();
    }

    @Test
    @DisplayName("T2 PAYMENT_EXPIRED·reserved>0 → 삭제 이연 + OrderTerminated 재발행 + AFTER_COMMIT 재고 해제·order 잔존")
    void expiredOrder_reservedPositive_defersAndRepublishes() {
        seedGraph("PAYMENT_EXPIRED", OrderItemStatus.ORDERED, QTY);

        expiredOrderCleanupService.cleanupOne(ORDER_ID);

        assertThat(orderExists()).isTrue();                 // 이번 회차 삭제 이연
        assertThat(orderItemCount()).isEqualTo(1);
        assertThat(applicationEvents.stream(OrderTerminated.class).count()).isEqualTo(1);
        assertThat(reserved()).isZero();                    // AFTER_COMMIT 핸들러가 예약 해제
        assertThat(available()).isEqualTo(10);
    }

    @Test
    @DisplayName("T3 status가 PAYMENT_EXPIRED 아님(PENDING_PAYMENT) → skip·삭제 없음")
    void nonExpiredOrder_skips() {
        seedGraph("PENDING_PAYMENT", OrderItemStatus.ORDERED, 0);

        expiredOrderCleanupService.cleanupOne(ORDER_ID);

        assertThat(orderExists()).isTrue();
        assertThat(orderItemCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T4 PENDING payment 존재 → skip·삭제 없음(결제 진행 가능성 방어)")
    void pendingPaymentPresent_skips() {
        seedGraph("PAYMENT_EXPIRED", OrderItemStatus.ORDERED, 0);
        seedPayment("PENDING");

        expiredOrderCleanupService.cleanupOne(ORDER_ID);

        assertThat(orderExists()).isTrue();
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T5 손자(delivery) 존재 → order_item 삭제 FK RESTRICT → DataIntegrityViolationException·전체 롤백(부분 삭제 없음)")
    void grandchildPresent_restrictViolation_rollsBack() {
        seedGraph("PAYMENT_EXPIRED", OrderItemStatus.ORDERED, 0);
        seedSnapshot();
        seedPayment("EXPIRED");
        seedDelivery();

        assertThatThrownBy(() -> expiredOrderCleanupService.cleanupOne(ORDER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 부분 삭제 방지: payment·snapshot 삭제도 롤백되어 그래프 전량 잔존
        assertThat(orderExists()).isTrue();
        assertThat(orderItemCount()).isEqualTo(1);
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(paymentCount()).isEqualTo(1);
    }

    // ---------- 시드·helpers ----------

    private void seedGraph(String orderStatus, OrderItemStatus itemStatus, int reserved) {
        seed(() -> {
            seedCatalog();
            seedInventory(10, reserved, 10 - reserved);
            seedOrder(orderStatus);
            seedOrderItem(itemStatus);
        });
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

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
                USER_ID, pid("usr_", "F122USR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'F122셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "F122SLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'F122상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "F122PRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCF122', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "F122VAR"), PRODUCT_ID, DUMMY_FK_ID);
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
                ORDER_ID, ORDER_PID, USER_ID, "ORDF122" + ORDER_ID, status);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 10000, 20000, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, QTY, itemStatus.name());
    }

    private void seedSnapshot() {
        seed(() -> jdbc.update("INSERT INTO order_shipping_snapshot (id, order_id, recipient_name, recipient_phone, "
                        + "zonecode, address_road, created_at, updated_at) "
                        + "VALUES (?, ?, '수령인', '01000000000', '12345', '도로명', NOW(6), NOW(6))",
                SNAPSHOT_ID, ORDER_ID));
    }

    private void seedPayment(String status) {
        seed(() -> jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, "
                        + "payment_attempt_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CARD', 20000, ?, ?, NOW(6), NOW(6))",
                PAYMENT_ID, PAYMENT_PID, ORDER_ID, status, PAYMENT_ATTEMPT_KEY));
    }

    private void seedDelivery() {
        seed(() -> jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CJ', 'READY', NOW(6), NOW(6))",
                DELIVERY_ID, DELIVERY_PID, ORDER_ITEM_ID));
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM delivery WHERE id = ?", DELIVERY_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE id = ?", SNAPSHOT_ID);
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

    private boolean orderExists() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE id = ?", Integer.class, ORDER_ID);
        return count != null && count > 0;
    }

    private int orderItemCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE order_id = ?", Integer.class, ORDER_ID);
    }

    private int snapshotCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_shipping_snapshot WHERE order_id = ?", Integer.class, ORDER_ID);
    }

    private int paymentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, ORDER_ID);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    private int available() {
        return jdbc.queryForObject("SELECT quantity_available FROM inventory WHERE variant_id = ?", Integer.class, VARIANT_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

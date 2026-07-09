package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.event.OrderTerminated;
import com.zslab.mall.order.service.OrderAutoCancelService;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미결제 주문 종료 E2E 통합 테스트(FE-12c·D-153 레벨3·실 MariaDB·Flyway). {@link OrderAutoCancelService#cancelOne}을
 * 비-테스트-트랜잭션으로 직접 호출해 PAYMENT_EXPIRED 종료(Order.status 직접 세팅·OrderItem 무변경)와 커밋 후 AFTER_COMMIT
 * {@code InventoryOrderTerminatedHandler} 재고 예약 해제까지의 실 커밋 경로를 검증한다({@code PaymentExpiryIntegrationTest} 구조 미러).
 *
 * <p><b>스케줄러 자동 발화 차단</b>: {@code zslab.order.auto-cancel.enabled=false}로 {@code @Scheduled} 배치를 끄고
 * {@code cancelOne}을 직접 호출해 결정론을 확보한다(프로젝트에 test profile 부재). createdAt 유예 조건은 스케줄러 조회
 * 책임이며 본 테스트는 cancelOne의 status 가드·종료·재고 해제만 검증하므로 order created_at은 NOW로 시드한다(tz 무관).
 */
@RecordApplicationEvents
@TestPropertySource(properties = "zslab.order.auto-cancel.enabled=false")
class OrderAutoCancelIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9801L;
    private static final long SELLER_ID = 9801L;
    private static final long PRODUCT_ID = 9801L;
    private static final long VARIANT_ID = 9801L;
    private static final long INVENTORY_ID = 9801L;
    private static final long ORDER_ID = 9801L;
    private static final long ORDER_ITEM_ID = 9801L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9801L;
    private static final int QTY = 2;

    private static final String ORDER_PID = pid("ord_", "D153ORD");
    private static final String ORDER_ITEM_PID = pid("oit_", "D153OIT");

    @Autowired
    private OrderAutoCancelService orderAutoCancelService;
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
    @DisplayName("T1 PENDING_PAYMENT → cancelOne → PAYMENT_EXPIRED 종료(OrderItem 무변경) + AFTER_COMMIT 재고 예약 해제")
    void pendingOrder_terminates_andReleasesReservation() {
        seedGraph("PENDING_PAYMENT", OrderItemStatus.ORDERED, QTY);

        // cancelOne은 자체 @Transactional — 직접 호출 시 커밋되어 AFTER_COMMIT 핸들러가 동기 발화한다.
        orderAutoCancelService.cancelOne(ORDER_ID);

        assertThat(orderStatus()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(orderItemStatus()).isEqualTo("ORDERED");   // OrderItem 무변경(재고 해제는 variant_id 기반)
        assertThat(applicationEvents.stream(OrderTerminated.class).count()).isEqualTo(1);
        assertThat(reserved()).isZero();
        assertThat(available()).isEqualTo(10);
        assertThat(onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("T2 이미 PAID → cancelOne skip(멱등) → 상태 불변·재고 해제 없음·이벤트 없음")
    void paidOrder_skipsTermination() {
        seedGraph("PAID", OrderItemStatus.PAID, QTY);

        orderAutoCancelService.cancelOne(ORDER_ID);

        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(orderItemStatus()).isEqualTo("PAID");
        assertThat(applicationEvents.stream(OrderTerminated.class).count()).isZero();
        assertThat(reserved()).isEqualTo(QTY);   // 해제되지 않음
    }

    // ---------- 시드·helpers ----------

    /** catalog + inventory + order + orderItem 그래프를 시드한다(order·item status·reserved 지정). */
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
                USER_ID, pid("usr_", "D153USR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'D153셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "D153SLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'D153상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "D153PRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCD153', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "D153VAR"), PRODUCT_ID, DUMMY_FK_ID);
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
                ORDER_ID, ORDER_PID, USER_ID, "ORDD153" + ORDER_ID, status);
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

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }

    private String orderItemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
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

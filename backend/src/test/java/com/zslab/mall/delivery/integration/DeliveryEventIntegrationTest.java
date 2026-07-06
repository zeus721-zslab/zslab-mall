package com.zslab.mall.delivery.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.order.enums.OrderItemStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * Delivery 이벤트(E4·E5) E2E 통합 테스트(Track 13·D-97 Q10·실 MariaDB·Flyway). {@code DeliveryService.markShipping}·
 * {@code markDelivered}를 커밋 트랜잭션에서 호출 → 동기 {@code order/handler/Delivery*Handler}(OrderItem 전이) +
 * 비동기 {@code notification/handler/NotificationDelivery*Handler}(AFTER_COMMIT·REQUIRES_NEW·NotificationLog 적재)까지
 * 실제 커밋 경로로 검증한다.
 *
 * <p><b>트랜잭션(NotificationLogIntegrationTest 패턴·D-90 Q5 β)</b>: AFTER_COMMIT 핸들러는 커밋 후에만 실행되므로 클래스에
 * {@code @Transactional}을 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}으로 커밋하고,
 * 검증은 {@link JdbcTemplate} 직접 조회로 한다. 발행도 {@link TransactionTemplate} 안에서 수행해 그 커밋 시점에 핸들러가
 * 동기적으로 발화한다(@Async 아님).
 *
 * <p><b>FK 정합(D-91)</b>: delivery INSERT·OrderItem UPDATE는 order_item FK 및 그 상위 그래프를 재검증하므로
 * user·seller·product·product_variant·order·order_item·delivery를 모두 시드한다.
 *
 * <p><b>LT-02</b>: {@code SET FOREIGN_KEY_CHECKS=0}은 try-finally로 {@code =1} 복원과 1:1 짝을 이룬다.
 */
class DeliveryEventIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9401L;
    private static final long SELLER_ID = 9401L;
    private static final long PRODUCT_ID = 9401L;
    private static final long VARIANT_ID = 9401L;
    private static final long ORDER_ID = 9401L;
    private static final long ORDER_ITEM_ID = 9401L;
    private static final long DELIVERY_ID = 9401L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9401L;
    private static final long FULL_AMOUNT = 10_000L;
    private static final String TRACKING_NO = "CJ-T13-TRACK-0001";

    @Autowired
    private DeliveryService deliveryService;
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
    @DisplayName("T1 markShipping → E4 발행 → Delivery SHIPPING·OrderItem SHIPPING·NotificationLog 1건(TPL_DELIVERY_STARTED)")
    void markShipping_publishesE4_transitionsOrderItem_recordsNotification() {
        seedGraph(DeliveryStatus.READY, OrderItemStatus.PREPARING);

        tx.executeWithoutResult(s -> deliveryService.markShipping(DELIVERY_ID, TRACKING_NO));

        assertThat(deliveryStatus()).isEqualTo("SHIPPING");
        assertThat(trackingNo()).isEqualTo(TRACKING_NO);
        assertThat(itemStatus()).isEqualTo("SHIPPING");
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(notificationTemplate()).isEqualTo("TPL_DELIVERY_STARTED");
        assertThat(notificationRecipient()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("T2 markDelivered → E5 발행 → Delivery DELIVERED·OrderItem DELIVERED·NotificationLog 1건(TPL_DELIVERY_COMPLETED)")
    void markDelivered_publishesE5_transitionsOrderItem_recordsNotification() {
        seedGraph(DeliveryStatus.SHIPPING, OrderItemStatus.SHIPPING);

        tx.executeWithoutResult(s -> deliveryService.markDelivered(DELIVERY_ID));

        assertThat(deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(itemStatus()).isEqualTo("DELIVERED");
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(notificationTemplate()).isEqualTo("TPL_DELIVERY_COMPLETED");
        assertThat(notificationRecipient()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("T3 잘못된 전이(READY → markDelivered 스킵) → IllegalStateException·상태 무변경·NotificationLog 미적재")
    void markDelivered_fromReady_throwsAndNoStateChange() {
        seedGraph(DeliveryStatus.READY, OrderItemStatus.PREPARING);

        assertThatThrownBy(() ->
                tx.executeWithoutResult(s -> deliveryService.markDelivered(DELIVERY_ID)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(deliveryStatus()).isEqualTo("READY");
        assertThat(itemStatus()).isEqualTo("PREPARING");
        assertThat(notificationCount()).isZero();
    }

    // ---------- seed·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedGraph(DeliveryStatus deliveryStatus, OrderItemStatus itemStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T13USR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙13셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "T13SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙13상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "T13PRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCT13', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "T13VAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "T13ORD"), USER_ID, "ORDT13" + ORDER_ID, FULL_AMOUNT);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                        ORDER_ITEM_ID, pid("oit_", "T13OIT"), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                        FULL_AMOUNT, FULL_AMOUNT, itemStatus.name());
                // delivery: SHIPPING 이상이면 tracking_no·shipped_at을 함께 시드(DLV-1·DLV-3 정합).
                if (deliveryStatus == DeliveryStatus.READY) {
                    jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, status, created_at, updated_at) "
                                    + "VALUES (?, ?, ?, 'CJ', ?, NOW(6), NOW(6))",
                            DELIVERY_ID, pid("dlv_", "T13DLV"), ORDER_ITEM_ID, deliveryStatus.name());
                } else {
                    jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, tracking_no, status, "
                                    + "shipped_at, created_at, updated_at) VALUES (?, ?, ?, 'CJ', ?, ?, NOW(6), NOW(6), NOW(6))",
                            DELIVERY_ID, pid("dlv_", "T13DLV"), ORDER_ITEM_ID, TRACKING_NO, deliveryStatus.name());
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE target_type = 'DELIVERY' AND target_id = ?", DELIVERY_ID);
                jdbc.update("DELETE FROM delivery WHERE id = ?", DELIVERY_ID);
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

    private String deliveryStatus() {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE id = ?", String.class, DELIVERY_ID);
    }

    private String trackingNo() {
        return jdbc.queryForObject("SELECT tracking_no FROM delivery WHERE id = ?", String.class, DELIVERY_ID);
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private int notificationCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE target_type = 'DELIVERY' AND target_id = ?",
                Integer.class, DELIVERY_ID);
    }

    private String notificationTemplate() {
        return jdbc.queryForObject(
                "SELECT template_code FROM notification_log WHERE target_type = 'DELIVERY' AND target_id = ?",
                String.class, DELIVERY_ID);
    }

    private long notificationRecipient() {
        return jdbc.queryForObject(
                "SELECT recipient_user_id FROM notification_log WHERE target_type = 'DELIVERY' AND target_id = ?",
                Long.class, DELIVERY_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

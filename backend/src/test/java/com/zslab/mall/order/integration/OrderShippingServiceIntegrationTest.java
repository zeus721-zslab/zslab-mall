package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.service.OrderShippingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 일반 주문 배송 개시 façade({@code OrderShippingService.prepareShipment}) 배선 검증 E2E 통합 테스트(Track 23·실 MariaDB·Flyway).
 * 단일 트랜잭션에서 OrderItem PAID→PREPARING → Delivery 생성(READY) → markShipping(E4 동기 소비) →
 * OrderItem PREPARING→SHIPPING → Order.status 재계산까지 실제 커밋 경로로 검증한다.
 *
 * <p><b>트랜잭션(DeliveryEventIntegrationTest 패턴)</b>: AFTER_COMMIT 알림 핸들러가 커밋 후 실행되므로 클래스에
 * {@code @Transactional}을 두지 않는다. 시드/정리·발행은 {@link TransactionTemplate}로 커밋하고 검증은 {@link JdbcTemplate}로 한다.
 *
 * <p><b>가드 B 회귀(T2)</b>: changeToPreparing 없이 createForOrder+markShipping을 직접 실행하면 OrderItem이 PAID인 채 E4가
 * 발화하여 {@code DeliveryStartedHandler}가 warn+return(조용한 skip)으로 OrderItem을 PAID로 잔류시키는 트랩을 실증·회귀 방지한다.
 *
 * <p><b>LT-02</b>: {@code SET FOREIGN_KEY_CHECKS=0}은 try-finally로 {@code =1} 복원과 1:1 짝을 이룬다.
 */
@SpringBootTest
class OrderShippingServiceIntegrationTest {

    private static final long USER_ID = 9423L;
    private static final long SELLER_ID = 9423L;
    private static final long WRONG_SELLER_ID = 8423L;
    private static final long PRODUCT_ID = 9423L;
    private static final long VARIANT_ID = 9423L;
    private static final long ORDER_ID = 9423L;
    private static final long ORDER_ITEM_ID = 9423L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9423L;
    private static final long FULL_AMOUNT = 10_000L;
    private static final String TRACKING_NO = "T23-TRACK-0001";

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
    private OrderShippingService orderShippingService;
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
    @DisplayName("T1 prepareShipment(PAID OrderItem) → OrderItem SHIPPING·Order SHIPPING·Delivery SHIPPING(claim_id NULL)·E4 발행")
    void prepareShipment_paidOrderItem_transitionsToShipping_publishesE4() {
        seedPaidOrderItem();

        Delivery result = tx.execute(status ->
                orderShippingService.prepareShipment(SELLER_ID, ORDER_ITEM_ID, DeliveryCarrier.CJ, TRACKING_NO));

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SHIPPING);
        assertThat(itemStatus()).isEqualTo("SHIPPING");
        assertThat(orderStatus()).isEqualTo("SHIPPING");
        assertThat(deliveryStatusByOrderItem()).isEqualTo("SHIPPING");
        assertThat(deliveryTrackingByOrderItem()).isEqualTo(TRACKING_NO);
        assertThat(deliveryClaimIdByOrderItem()).isNull();
        assertThat(deliveryStartedNotifications()).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 가드 B 역행: changeToPreparing 없이 createForOrder+markShipping → OrderItem PAID 잔류(조용한 skip)·Delivery만 SHIPPING")
    void markShippingWithoutPreparing_leavesOrderItemPaid() {
        seedPaidOrderItem();

        tx.executeWithoutResult(status -> {
            Delivery delivery = deliveryService.createForOrder(ORDER_ITEM_ID, DeliveryCarrier.CJ);
            deliveryService.markShipping(delivery.getId(), TRACKING_NO);
        });

        // DeliveryStartedHandler 가드가 PAID→SHIPPING 불가로 warn+return → OrderItem·Order는 PAID 잔류(데이터 불일치 트랩)
        assertThat(itemStatus()).isEqualTo("PAID");
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(deliveryStatusByOrderItem()).isEqualTo("SHIPPING");
    }

    @Test
    @DisplayName("T3 권한 위반(타 seller) → OrderNotFoundException(404·존재 은닉)·OrderItem PAID 무변경·Delivery 미생성")
    void prepareShipment_wrongSeller_throwsNotFound_noStateChange() {
        seedPaidOrderItem();

        assertThatThrownBy(() -> tx.execute(status ->
                orderShippingService.prepareShipment(WRONG_SELLER_ID, ORDER_ITEM_ID, DeliveryCarrier.CJ, TRACKING_NO)))
                .isInstanceOf(OrderNotFoundException.class);

        assertThat(itemStatus()).isEqualTo("PAID");
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(deliveryCountByOrderItem()).isZero();
    }

    // ---------- seed·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedPaidOrderItem() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T23USR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙23셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "T23SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '트랙23상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "T23PRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCT23', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "T23VAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "T23ORD"), USER_ID, "ORDT23" + ORDER_ID, FULL_AMOUNT);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                                + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'PAID', NOW(6), NOW(6))",
                        ORDER_ITEM_ID, pid("oit_", "T23OIT"), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                        FULL_AMOUNT, FULL_AMOUNT);
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
                jdbc.update("DELETE FROM delivery WHERE order_item_id = ?", ORDER_ITEM_ID);
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

    private String itemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }

    private String deliveryStatusByOrderItem() {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE order_item_id = ?", String.class, ORDER_ITEM_ID);
    }

    private String deliveryTrackingByOrderItem() {
        return jdbc.queryForObject("SELECT tracking_no FROM delivery WHERE order_item_id = ?", String.class, ORDER_ITEM_ID);
    }

    private Long deliveryClaimIdByOrderItem() {
        return jdbc.queryForObject("SELECT claim_id FROM delivery WHERE order_item_id = ?", Long.class, ORDER_ITEM_ID);
    }

    private int deliveryCountByOrderItem() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE order_item_id = ?", Integer.class, ORDER_ITEM_ID);
    }

    private int deliveryStartedNotifications() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ? AND template_code = 'TPL_DELIVERY_STARTED'",
                Integer.class, USER_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

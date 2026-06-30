package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.handler.ExchangeDeliveryCompletedHandler;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.event.DeliveryCompleted;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;
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
 * EXCHANGE 클레임 전체 루프 E2E 통합 테스트(Track 14 PR-2·D-98 Q3·Q5·Q13·실 MariaDB). 요청 → 승인 → 수거 확인 →
 * 교환품 출고({@code DeliveryService.registerExchangeShipment}) → 배송 완료({@code markDelivered}) → DeliveryCompleted(E5)
 * → {@code ExchangeDeliveryCompletedHandler}(AFTER_COMMIT)까지 실제 커밋·핸들러 체인으로 구동해 OrderItem
 * DELIVERED → EXCHANGE_REQUESTED → EXCHANGED·Claim COMPLETED 종결을 실측한다(라이브 트랩 차단).
 *
 * <p><b>트랜잭션</b>: AFTER_COMMIT 핸들러는 실제 커밋 후 동기 실행되므로 클래스에 {@code @Transactional}을 두지 않는다
 * (ClaimReturnIntegrationTest 패턴 1:1). 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0},
 * 검증은 {@link JdbcTemplate} 직접 조회로 한다.
 */
@SpringBootTest
class ClaimExchangeIntegrationTest {

    private static final long USER_ID = 9402L;
    private static final long SELLER_ID = 9402L;
    private static final long PRODUCT_ID = 9402L;
    private static final long VARIANT_ID = 9402L;
    private static final long ORDER_ID = 9402L;
    private static final long ORDER_ITEM_ID = 9402L;
    private static final long DUMMY_FK_ID = 9402L;
    private static final long ITEM_PRICE = 10_000L;

    private static final long SEEDED_RETURN_CLAIM_ID = 9402L;
    private static final long SEEDED_DELIVERY_ID = 9402L;

    private static final String ORDER_ITEM_PID = pid("oit_", "EXCOIT");

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
    private ClaimService claimService;
    @Autowired
    private DeliveryService deliveryService;
    @Autowired
    private ExchangeDeliveryCompletedHandler exchangeDeliveryCompletedHandler;
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
    @DisplayName("EXCHANGE 전체 루프 e2e: 요청→승인→수거→출고→배송완료 → OrderItem EXCHANGED·Claim COMPLETED")
    void exchangeFullLoop_terminatesToExchanged() {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.DELIVERED);
        });

        long[] ids = runExchangeToDelivered();

        assertThat(orderItemStatus()).isEqualTo("EXCHANGED");
        assertThat(claimStatus(ids[0])).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("동일 DeliveryCompleted(E5) 2회 소비 → EXCHANGED·COMPLETED 유지·CLAIM_COMPLETED 알림 중복 없음(R3)")
    void duplicateDeliveryCompleted_isIdempotent() {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.DELIVERED);
        });

        long[] ids = runExchangeToDelivered();
        long claimId = ids[0];
        long deliveryId = ids[1];
        assertThat(orderItemStatus()).isEqualTo("EXCHANGED");
        int notiCountAfterFirst = completedNotificationCount(claimId);

        // 동일 E5 재소비(핸들러 직접 재호출·@Transactional REQUIRES_NEW 프록시). markCompleted 멱등 가드가 E9 재발행을 차단한다.
        exchangeDeliveryCompletedHandler.handle(
                new DeliveryCompleted(deliveryId, ORDER_ITEM_ID, LocalDateTime.now(), LocalDateTime.now()));

        assertThat(orderItemStatus()).isEqualTo("EXCHANGED");
        assertThat(claimStatus(claimId)).isEqualTo("COMPLETED");
        assertThat(completedNotificationCount(claimId)).isEqualTo(notiCountAfterFirst);
    }

    @Test
    @DisplayName("type 불일치 방어: claim_id가 RETURN 클레임을 가리키면 교환 핸들러 skip·Claim·OrderItem 미변경")
    void typeMismatch_handlerSkips() {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.DELIVERED);
            seedApprovedReturnClaim();
            seedShippingDeliveryLinkedToClaim(SEEDED_RETURN_CLAIM_ID);
        });

        deliveryService.markDelivered(SEEDED_DELIVERY_ID);

        // 교환 핸들러는 type=RETURN → log.warn skip·order 핸들러는 claim_id != null → 미전이
        assertThat(claimStatus(SEEDED_RETURN_CLAIM_ID)).isEqualTo("APPROVED");
        assertThat(orderItemStatus()).isEqualTo("DELIVERED");
    }

    // ---------- 흐름 helper ----------

    /** 요청→승인→수거→출고→배송완료 전 구간을 실 서비스로 구동하고 {claimId, deliveryId}를 반환한다. */
    private long[] runExchangeToDelivered() {
        Claim claim = claimService.request(new ClaimRequestCommand(
                ORDER_ITEM_PID, ClaimType.EXCHANGE, ClaimReasonCode.PRODUCT_DEFECT, "하자", USER_ID, LocalDateTime.now()));
        Long claimId = claim.getId();
        assertThat(orderItemStatus()).isEqualTo("EXCHANGE_REQUESTED");

        claimService.approve(claimId, LocalDateTime.now());
        claimService.confirmPickup(claimId, LocalDateTime.now()); // EXCHANGE는 자동 환불 미대상(ClaimPickedUpHandler skip)

        Delivery delivery = deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-0001");
        deliveryService.markDelivered(delivery.getId());
        return new long[] {claimId, delivery.getId()};
    }

    // ---------- seed·helpers (ClaimReturnIntegrationTest 패턴 1:1) ----------

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
                USER_ID, pid("usr_", "EXCUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "EXCSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "EXCPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCEXC', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "EXCVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedOrder(String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, pid("ord_", "EXCORD"), USER_ID, "ORDEXC" + ORDER_ID, status, ITEM_PRICE);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                ITEM_PRICE, ITEM_PRICE, itemStatus.name());
    }

    /** type 불일치 방어(T3)용 RETURN·APPROVED 클레임 시드. */
    private void seedApprovedReturnClaim() {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, "
                        + "previous_order_item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', 'APPROVED', 'DELIVERED', NOW(6), NOW(6))",
                SEEDED_RETURN_CLAIM_ID, pid("clm_", "EXCRTN"), ORDER_ITEM_ID);
    }

    /** type 불일치 방어(T3)용 SHIPPING·claim_id 연결 Delivery 시드(markDelivered 진입 가능 상태). */
    private void seedShippingDeliveryLinkedToClaim(long claimId) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, tracking_no, status, shipped_at, "
                        + "claim_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CJ', 'CJ-EXC-T3', 'SHIPPING', NOW(6), ?, NOW(6), NOW(6))",
                SEEDED_DELIVERY_ID, pid("dlv_", "EXCDLV"), ORDER_ITEM_ID, claimId);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id = ?)",
                        ORDER_ITEM_ID);
                jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
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

    private String orderItemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private String claimStatus(Long claimId) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId);
    }

    /** CLAIM_COMPLETED 알림 적재 건수(E9 재발행 차단 검증용). template_code는 상수·target_id는 ? 바인딩(SQL injection 위험 없음). */
    private int completedNotificationCount(Long claimId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE template_code = 'TPL_CLAIM_COMPLETED' AND target_id = ?",
                Integer.class, claimId);
        return count == null ? 0 : count;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

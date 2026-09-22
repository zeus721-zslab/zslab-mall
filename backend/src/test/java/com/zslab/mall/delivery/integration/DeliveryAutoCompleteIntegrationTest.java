package com.zslab.mall.delivery.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.zslab.mall.dashboard.repository.AdminDashboardRepository;
import com.zslab.mall.dashboard.repository.SellerDashboardRepository;
import com.zslab.mall.delivery.adapter.DeliveryTracker;
import com.zslab.mall.delivery.adapter.DeliveryTrackingStatus;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.policy.LongShippingThreshold;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.scheduler.DeliveryAutoCompleteScheduler;
import com.zslab.mall.delivery.service.DeliveryAutoCompleteService;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자동 배송완료 배치 통합 테스트(Track 99 D-210·실 MariaDB). 배달 완료 건만 DELIVERED로 전이되고 품목·주문 상태가 함께 갱신되는지,
 * 이동중·회수(RETURN)·이미 배송완료(재확인 불일치)는 건드리지 않는지, 클레임 연결 발송(재발송·교환품)도 대상에 들어가 기존 소비처로
 * 넘어가는지, 감사 로그가 SYSTEM 1행으로 남는지, 대시보드 장기 배송중 집계가 경계·셀러 격리를 지키는지를 실제 커밋으로 검증한다.
 * (클레임 연결 발송의 종결 처리 자체는 수동 경로와 같은 {@code ExchangeDeliveryCompletedHandler}라 Track 83 테스트가 담당한다 —
 * 여기서는 자동 경로가 그 핸들러까지 도달하는지만 본다.)
 *
 * <p><b>스케줄러 자동 발화 차단</b>: {@code zslab.delivery.auto-complete.enabled=false}로 {@code @Scheduled} 빈을 끄고 배치는
 * {@link DeliveryAutoCompleteScheduler}를 직접 생성해 호출한다(킬스위치 검증 겸·{@code OrderAutoConfirmIntegrationTest} 패턴).
 * 배송 조회는 {@link DeliveryTracker}를 mock으로 갈아 끼워 외부 시간 의존을 없앤다.
 */
@TestPropertySource(properties = {
        "zslab.delivery.auto-complete.enabled=false",
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class DeliveryAutoCompleteIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9602L;
    private static final long SELLER_A = 9602L;
    private static final long SELLER_B = 9603L;
    private static final long PRODUCT_A = 9602L;
    private static final long PRODUCT_B = 9603L;
    private static final long VARIANT_A = 9602L;
    private static final long VARIANT_B = 9603L;
    private static final long DUMMY_FK_ID = 9602L;
    private static final long ITEM_PRICE = 12_000L;

    /** 시드 품목·배송 id = BASE + n(주문·품목·배송 동일 번호). */
    private static final long BASE = 9610L;
    private static final long ITEM_DELIVERED_OK = BASE + 1;   // 발송 5일·조회 배달완료 → 전이
    private static final long ITEM_IN_TRANSIT = BASE + 2;     // 발송 5일·조회 이동중 → 유지
    private static final long ITEM_RETURN = BASE + 3;         // 회수(RETURN) 배송 → 후보에서 제외
    private static final long ITEM_ALREADY = BASE + 4;        // 이미 DELIVERED 배송 → 후보에서 제외
    private static final long ITEM_RECENT = BASE + 5;         // 발송 1일 → 장기 배송중 경계 검증용(조회는 이동중)
    private static final long ITEM_SELLER_B = BASE + 6;       // 셀러 B 발송 5일 → 셀러 격리 검증용(조회는 이동중)
    private static final long ITEM_RESHIP = BASE + 7;         // 클레임 연결 발송(재발송) 5일·조회 배달완료 → 전이(품목은 핸들러가 건너뜀)
    private static final long RETURN_CLAIM_ID = 999_602L;
    private static final long RESHIP_CLAIM_ID = 999_603L;

    @MockitoBean
    private DeliveryTracker deliveryTracker;
    @MockitoBean
    private SmsSender smsSender;

    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private DeliveryAutoCompleteService deliveryAutoCompleteService;
    @Autowired
    private AdminDashboardRepository adminDashboardRepository;
    @Autowired
    private SellerDashboardRepository sellerDashboardRepository;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private DeliveryAutoCompleteScheduler scheduler;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        doNothing().when(smsSender).send(any(), any());
        scheduler = new DeliveryAutoCompleteScheduler(deliveryRepository, deliveryTracker, deliveryAutoCompleteService);
        cleanup();
        seed(() -> {
            seedCatalog();
            seedOrderWithItem(ITEM_DELIVERED_OK, SELLER_A, PRODUCT_A, VARIANT_A, "SHIPPING");
            seedDelivery(ITEM_DELIVERED_OK, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, 5, null);
            seedOrderWithItem(ITEM_IN_TRANSIT, SELLER_A, PRODUCT_A, VARIANT_A, "SHIPPING");
            seedDelivery(ITEM_IN_TRANSIT, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, 5, null);
            seedOrderWithItem(ITEM_RETURN, SELLER_A, PRODUCT_A, VARIANT_A, "RETURN_REQUESTED");
            seedDelivery(ITEM_RETURN, DeliveryDirection.RETURN, DeliveryStatus.SHIPPING, 5, RETURN_CLAIM_ID);
            seedOrderWithItem(ITEM_ALREADY, SELLER_A, PRODUCT_A, VARIANT_A, "DELIVERED");
            seedDelivery(ITEM_ALREADY, DeliveryDirection.OUTBOUND, DeliveryStatus.DELIVERED, 5, null);
            seedOrderWithItem(ITEM_RECENT, SELLER_A, PRODUCT_A, VARIANT_A, "SHIPPING");
            seedDelivery(ITEM_RECENT, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, 1, null);
            seedOrderWithItem(ITEM_SELLER_B, SELLER_B, PRODUCT_B, VARIANT_B, "SHIPPING");
            seedDelivery(ITEM_SELLER_B, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, 5, null);
            seedOrderWithItem(ITEM_RESHIP, SELLER_A, PRODUCT_A, VARIANT_A, "DELIVERED");
            seedRejectedReturnClaim(RESHIP_CLAIM_ID, ITEM_RESHIP);
            seedDelivery(ITEM_RESHIP, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, 5, RESHIP_CLAIM_ID);
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 배치: 배달완료 건만 DELIVERED(품목·주문 재계산) · 이동중 유지 · 회수(RETURN)·이미 배송완료는 후보 제외 · 재실행 멱등")
    void batch_completesOnlyTrackedDelivered() {
        trackAs(ITEM_DELIVERED_OK, DeliveryTrackingStatus.DELIVERED);
        trackAs(ITEM_IN_TRANSIT, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_RECENT, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_SELLER_B, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_RESHIP, DeliveryTrackingStatus.DELIVERED);

        scheduler.completeBatch();

        assertThat(deliveryStatus(ITEM_DELIVERED_OK)).isEqualTo("DELIVERED");
        assertThat(deliveredAt(ITEM_DELIVERED_OK)).isNotNull();
        assertThat(itemStatus(ITEM_DELIVERED_OK)).isEqualTo("DELIVERED");
        assertThat(orderStatus(ITEM_DELIVERED_OK)).isEqualTo("DELIVERED");

        assertThat(deliveryStatus(ITEM_IN_TRANSIT)).isEqualTo("SHIPPING");
        assertThat(itemStatus(ITEM_IN_TRANSIT)).isEqualTo("SHIPPING");
        // 회수(RETURN)·이미 배송완료 건은 후보 쿼리에서 빠져 조회조차 하지 않는다.
        assertThat(deliveryStatus(ITEM_RETURN)).isEqualTo("SHIPPING");
        assertThat(itemStatus(ITEM_RETURN)).isEqualTo("RETURN_REQUESTED");
        assertThat(deliveryStatus(ITEM_ALREADY)).isEqualTo("DELIVERED");

        // 클레임 연결 발송(재발송)도 배송은 완료되지만 품목 전이는 클레임 핸들러 몫이라 DeliveryCompletedHandler가 건너뛴다.
        assertThat(deliveryStatus(ITEM_RESHIP)).isEqualTo("DELIVERED");
        assertThat(itemStatus(ITEM_RESHIP)).isEqualTo("DELIVERED");

        LocalDateTime firstDeliveredAt = deliveredAt(ITEM_DELIVERED_OK);
        scheduler.completeBatch();
        assertThat(deliveredAt(ITEM_DELIVERED_OK)).isEqualTo(firstDeliveredAt);
    }

    @Test
    @DisplayName("T2 감사: 자동 전이 1건당 audit_log SYSTEM 1행(action UPDATE·target DELIVERY) · 전이 없으면 0행")
    void audit_systemRowPerTransition() {
        trackAs(ITEM_DELIVERED_OK, DeliveryTrackingStatus.DELIVERED);
        trackAs(ITEM_IN_TRANSIT, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_RECENT, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_SELLER_B, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_RESHIP, DeliveryTrackingStatus.IN_TRANSIT);

        scheduler.completeBatch();

        assertThat(auditCount(ITEM_DELIVERED_OK)).isEqualTo(1L);
        assertThat(auditActorRole(ITEM_DELIVERED_OK)).isEqualTo("SYSTEM");
        assertThat(auditActorUserId(ITEM_DELIVERED_OK)).isNull();
        assertThat(auditCount(ITEM_IN_TRANSIT)).isZero();
    }

    @Test
    @DisplayName("T3 재확인 불일치: 조회가 배달완료라도 그 사이 배송완료로 바뀌었으면 전이하지 않는다(completeOne=false)")
    void completeOne_skipsWhenNoLongerShipping() {
        assertThat(deliveryAutoCompleteService.completeOne(ITEM_ALREADY, DeliveryCarrier.CJ, trackingNo(ITEM_ALREADY)))
                .isFalse();
        assertThat(auditCount(ITEM_ALREADY)).isZero();

        // 회수 배송도 같은 재확인에서 막힌다(방향 가드).
        assertThat(deliveryAutoCompleteService.completeOne(ITEM_RETURN, DeliveryCarrier.CJ, trackingNo(ITEM_RETURN)))
                .isFalse();
        assertThat(deliveryStatus(ITEM_RETURN)).isEqualTo("SHIPPING");
    }

    @Test
    @DisplayName("T3-1 F1 송장 정정 경합: 조회 뒤 운영자가 송장을 정정하면(SHIPPING 유지) 그 조회 결과로 전이하지 않는다 — 전이·감사 0")
    void completeOne_skipsWhenTrackingCorrectedAfterTracking() {
        String trackedNo = trackingNo(ITEM_DELIVERED_OK);

        // 조회와 전이 사이에 송장 정정이 일어난 상황(correctTracking은 상태를 바꾸지 않아 상태 가드로는 걸리지 않는다).
        jdbc.update("UPDATE delivery SET carrier = 'HANJIN', tracking_no = ? WHERE id = ?",
                trackedNo + "-FIXED", ITEM_DELIVERED_OK);

        assertThat(deliveryAutoCompleteService.completeOne(ITEM_DELIVERED_OK, DeliveryCarrier.CJ, trackedNo)).isFalse();
        assertThat(deliveryStatus(ITEM_DELIVERED_OK)).isEqualTo("SHIPPING");
        assertThat(itemStatus(ITEM_DELIVERED_OK)).isEqualTo("SHIPPING");
        assertThat(auditCount(ITEM_DELIVERED_OK)).isZero();

        // 택배사만 바뀐 경우도 같다(송장번호는 그대로).
        jdbc.update("UPDATE delivery SET carrier = 'HANJIN', tracking_no = ? WHERE id = ?", trackedNo, ITEM_DELIVERED_OK);
        assertThat(deliveryAutoCompleteService.completeOne(ITEM_DELIVERED_OK, DeliveryCarrier.CJ, trackedNo)).isFalse();
        assertThat(deliveryStatus(ITEM_DELIVERED_OK)).isEqualTo("SHIPPING");

        // 정정된 송장으로 다시 조회한 결과라면 정상 전이된다(다음 실행이 이어받는 경로).
        assertThat(deliveryAutoCompleteService.completeOne(ITEM_DELIVERED_OK, DeliveryCarrier.HANJIN, trackedNo)).isTrue();
        assertThat(deliveryStatus(ITEM_DELIVERED_OK)).isEqualTo("DELIVERED");
        assertThat(auditCount(ITEM_DELIVERED_OK)).isEqualTo(1L);
    }

    @Test
    @DisplayName("T4 킬스위치: enabled=false 컨텍스트에 스케줄러 빈 없음")
    void killSwitch_beanAbsent() {
        assertThat(applicationContext.getBeanNamesForType(DeliveryAutoCompleteScheduler.class)).isEmpty();
    }

    @Test
    @DisplayName("T5 장기 배송중 집계: 경계 3일 이상만 계수 · 셀러는 본인 것만 · 전이하면 줄어든다")
    void longShipping_boundaryAndSellerIsolation() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(LongShippingThreshold.DAYS);

        // 시드 기준: 5일 경과 발송 SHIPPING 4건(A 3건·B 1건) 중 A의 ITEM_RETURN은 RETURN이라 제외 → A 2건·B 1건.
        long adminBefore = adminDashboardRepository.countLongShipping(
                DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, threshold);
        long sellerABefore = sellerDashboardRepository.countLongShipping(
                SELLER_A, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, threshold);
        long sellerBBefore = sellerDashboardRepository.countLongShipping(
                SELLER_B, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, threshold);
        // ITEM_DELIVERED_OK·ITEM_IN_TRANSIT·ITEM_RESHIP 3건(ITEM_RECENT 1일은 경계 미만·ITEM_RETURN은 회수·ITEM_ALREADY는 이미 완료)
        assertThat(sellerABefore).isEqualTo(3L);
        assertThat(sellerBBefore).isEqualTo(1L);
        assertThat(adminBefore).isGreaterThanOrEqualTo(sellerABefore + sellerBBefore);

        trackAs(ITEM_DELIVERED_OK, DeliveryTrackingStatus.DELIVERED);
        trackAs(ITEM_IN_TRANSIT, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_RECENT, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_SELLER_B, DeliveryTrackingStatus.IN_TRANSIT);
        trackAs(ITEM_RESHIP, DeliveryTrackingStatus.IN_TRANSIT);
        scheduler.completeBatch();

        assertThat(sellerDashboardRepository.countLongShipping(
                SELLER_A, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, threshold)).isEqualTo(2L);
        assertThat(sellerDashboardRepository.countLongShipping(
                SELLER_B, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, threshold)).isEqualTo(1L);
        assertThat(adminDashboardRepository.countLongShipping(
                DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING, threshold)).isEqualTo(adminBefore - 1);
    }

    // ---------- seed·helpers ----------

    private void trackAs(long deliveryId, DeliveryTrackingStatus status) {
        when(deliveryTracker.track(any(), org.mockito.ArgumentMatchers.eq("DAC-TRACK-" + deliveryId), any()))
                .thenReturn(status);
    }

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
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "DACUSR"), "dac@example.test", "자동배송완료구매자", "010-6666-7777");
        seedSeller(SELLER_A, "DACSLRA", "자동배송완료셀러A");
        seedSeller(SELLER_B, "DACSLRB", "자동배송완료셀러B");
        seedProduct(PRODUCT_A, VARIANT_A, SELLER_A, "DACA");
        seedProduct(PRODUCT_B, VARIANT_B, SELLER_B, "DACB");
    }

    private void seedSeller(long sellerId, String suffix, String name) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))",
                sellerId, pid("slr_", suffix), name);
    }

    private void seedProduct(long productId, long variantId, long sellerId, String suffix) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '자동배송완료상품', 'SALE', 12000, NOW(6), NOW(6))",
                productId, pid("prd_", suffix + "PRD"), sellerId, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                variantId, pid("var_", suffix + "VAR"), productId, "VC" + suffix, DUMMY_FK_ID);
    }

    /** 주문 1건 + 품목 1건(id 동일 번호). 주문 상태는 품목 상태에 맞춰 SHIPPING으로 두고 재계산 결과를 본다. */
    private void seedOrderWithItem(long id, long sellerId, long productId, long variantId, String itemStatus) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'SHIPPING', ?, 0, 0, NOW(6), NOW(6))",
                id, orderPid(id), USER_ID, "ORDDAC" + id, ITEM_PRICE);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '자동배송완료 상품', 1000)",
                id, itemPid(id), id, productId, variantId, sellerId, ITEM_PRICE, ITEM_PRICE, itemStatus);
    }

    /** 배송 1건(id = order_item_id). shipped_at = 지금 − daysAgo일. */
    private void seedDelivery(long orderItemId, DeliveryDirection direction, DeliveryStatus status, int daysAgo, Long claimId) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, "
                        + "shipped_at, delivered_at, claim_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CJ', ?, ?, NOW(6) - INTERVAL ? DAY, ?, ?, NOW(6), NOW(6))",
                orderItemId, pid("dlv_", "DACDLV" + orderItemId), orderItemId, direction.name(),
                "DAC-TRACK-" + orderItemId, status.name(), daysAgo,
                status == DeliveryStatus.DELIVERED ? LocalDateTime.now().minusDays(daysAgo) : null, claimId);
    }

    /** 검수 불합격(재발송) 상황의 RETURN 클레임 행. 재발송 배송이 claim_id로 연결된다. */
    private void seedRejectedReturnClaim(long claimId, long orderItemId) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, status, reason_code, requested_by, requested_at, "
                        + "previous_order_item_status, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'RETURN', 'REJECTED', 'PRODUCT_DEFECT', ?, NOW(6), 'DELIVERED', 0, NOW(6), NOW(6))",
                claimId, pid("clm_", "DACCLM" + claimId), orderItemId, USER_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'DELIVERY' AND target_id BETWEEN ? AND ?", BASE + 1, BASE + 7);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id BETWEEN ? AND ?", BASE + 1, BASE + 7);
                jdbc.update("DELETE FROM claim WHERE order_item_id BETWEEN ? AND ?", BASE + 1, BASE + 7);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", BASE + 1, BASE + 7);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", BASE + 1, BASE + 7);
                jdbc.update("DELETE FROM product_variant WHERE id IN (?, ?)", VARIANT_A, VARIANT_B);
                jdbc.update("DELETE FROM product WHERE id IN (?, ?)", PRODUCT_A, PRODUCT_B);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String trackingNo(long deliveryId) {
        return jdbc.queryForObject("SELECT tracking_no FROM delivery WHERE id = ?", String.class, deliveryId);
    }

    private String deliveryStatus(long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE id = ?", String.class, deliveryId);
    }

    private LocalDateTime deliveredAt(long deliveryId) {
        return jdbc.queryForObject("SELECT delivered_at FROM delivery WHERE id = ?", LocalDateTime.class, deliveryId);
    }

    private String itemStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, orderItemId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, orderId);
    }

    private long auditCount(long deliveryId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'DELIVERY' AND target_id = ? AND action = 'UPDATE'",
                Long.class, deliveryId);
        return count == null ? 0L : count;
    }

    private String auditActorRole(long deliveryId) {
        return jdbc.queryForObject(
                "SELECT actor_role FROM audit_log WHERE target_type = 'DELIVERY' AND target_id = ? ORDER BY id DESC LIMIT 1",
                String.class, deliveryId);
    }

    private Long auditActorUserId(long deliveryId) {
        return jdbc.queryForObject(
                "SELECT actor_user_id FROM audit_log WHERE target_type = 'DELIVERY' AND target_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, deliveryId);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    private static String orderPid(long id) {
        return pid("ord_", "DACORD" + id);
    }

    private static String itemPid(long id) {
        return pid("oit_", "DACOIT" + id);
    }
}

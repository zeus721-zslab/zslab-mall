package com.zslab.mall.delivery.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.delivery.adapter.DeliveryTracker;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.service.AdminDeliveryCommandService;
import com.zslab.mall.delivery.service.DeliveryAutoCompleteService;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배송 행 락 경합 통합 테스트(Track 99 외부 검토 4·실 MariaDB). {@code Delivery}에는 {@code @Version}도 {@code @DynamicUpdate}도 없어
 * 더티 체킹 UPDATE가 전 컬럼을 쓴다 — 락이 없으면 송장 정정 트랜잭션이 배송완료 커밋 뒤에 저장되면서 {@code status}·{@code delivered_at}을
 * 옛 값(SHIPPING·NULL)으로 되돌린다(lost update). 세 경합을 실제 스레드 2개로 재현해 락이 그것을 막는지 본다.
 *
 * <p>클래스에 {@code @Transactional}을 두지 않는다(행 락·실제 커밋 관찰). 스케줄러는 킬스위치로 끈다.
 */
@TestPropertySource(properties = {
        "zslab.delivery.auto-complete.enabled=false",
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class DeliveryLockRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9702L;
    private static final long SELLER_ID = 9702L;
    private static final long PRODUCT_ID = 9702L;
    private static final long VARIANT_ID = 9702L;
    private static final long DUMMY_FK_ID = 9702L;
    private static final long ITEM_PRICE = 13_000L;

    /** 시드 품목·배송 id = BASE + n. */
    private static final long BASE = 9710L;
    private static final long DLV_LOCK_HELD = BASE + 1;   // ① 자동 전이가 락 보유 중 정정 시도
    private static final long DLV_CORRECT_FIRST = BASE + 2; // ② 정정이 먼저 커밋 → 자동 skip(F1)
    private static final long DLV_BOTH_COMPLETE = BASE + 3; // ③ 수동·자동 배송완료 동시

    private static final String ADMIN_ROLE = "ADMIN";
    private static final long ADMIN_ACTOR_ID = 9702L;
    private static final int RACE_TIMEOUT_SECONDS = 30;

    @MockitoBean
    private DeliveryTracker deliveryTracker;
    @MockitoBean
    private SmsSender smsSender;

    @Autowired
    private DeliveryAutoCompleteService deliveryAutoCompleteService;
    @Autowired
    private AdminDeliveryCommandService adminDeliveryCommandService;
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
        doNothing().when(smsSender).send(any(), any());
        cleanup();
        seed(() -> {
            seedCatalog();
            seedOrderWithItem(DLV_LOCK_HELD);
            seedDelivery(DLV_LOCK_HELD);
            seedOrderWithItem(DLV_CORRECT_FIRST);
            seedDelivery(DLV_CORRECT_FIRST);
            seedOrderWithItem(DLV_BOTH_COMPLETE);
            seedDelivery(DLV_BOTH_COMPLETE);
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("R1 자동 전이가 락 보유 중 송장 정정 시도 → 정정은 대기 후 422 · 최종 DELIVERED·delivered_at 유지(lost update 없음)")
    void correctTrackingWaitsThenFailsWhileAutoCompleteHoldsLock() throws Exception {
        String trackedNo = trackingNo(DLV_LOCK_HELD);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch correctionAttempted = new CountDownLatch(1);

        // 자동 전이 트랜잭션: 행 락을 잡고(completeOne 내부 refresh PESSIMISTIC_WRITE) 정정 스레드가 붙을 때까지 붙들고 있다가 커밋한다.
        Callable<String> autoComplete = () -> tx.execute(status -> {
            boolean transitioned = deliveryAutoCompleteService.completeOne(DLV_LOCK_HELD, DeliveryCarrier.CJ, trackedNo);
            lockHeld.countDown();
            awaitQuietly(correctionAttempted, 5);
            return transitioned ? "COMPLETED" : "SKIPPED";
        });
        Callable<String> correct = () -> {
            awaitQuietly(lockHeld, RACE_TIMEOUT_SECONDS);
            correctionAttempted.countDown();
            try {
                adminDeliveryCommandService.correctTracking(publicId(DLV_LOCK_HELD), DeliveryCarrier.HANJIN,
                        trackedNo + "-FIX", "오입력 정정", AuditContext.of(ADMIN_ACTOR_ID, ADMIN_ROLE));
                return "CORRECTED";
            } catch (RuntimeException exception) {
                return exception.getClass().getSimpleName();
            }
        };

        Future<String> autoResult = pool.submit(autoComplete);
        Future<String> correctResult = pool.submit(correct);
        String autoOutcome = autoResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String correctOutcome = correctResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(autoOutcome).isEqualTo("COMPLETED");
        // 정정은 락이 풀린 뒤 최신 상태(DELIVERED)를 보고 422로 막힌다 — 락이 없으면 여기서 성공하며 status를 되돌린다.
        assertThat(correctOutcome).isEqualTo("DeliveryInvalidStateException");
        assertThat(deliveryStatus(DLV_LOCK_HELD)).isEqualTo("DELIVERED");
        assertThat(deliveredAt(DLV_LOCK_HELD)).isNotNull();
        assertThat(trackingNo(DLV_LOCK_HELD)).isEqualTo(trackedNo);
        assertThat(itemStatus(DLV_LOCK_HELD)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("R2 송장 정정이 먼저 커밋 → 자동 completeOne은 F1 재확인에서 skip · 전이·감사 0")
    void autoCompleteSkipsWhenCorrectionCommittedFirst() {
        String trackedNo = trackingNo(DLV_CORRECT_FIRST);

        adminDeliveryCommandService.correctTracking(publicId(DLV_CORRECT_FIRST), DeliveryCarrier.HANJIN,
                trackedNo + "-FIX", "오입력 정정", AuditContext.of(ADMIN_ACTOR_ID, ADMIN_ROLE));

        // 배치가 정정 전 송장으로 조회한 결과를 들고 들어온 상황.
        boolean transitioned = deliveryAutoCompleteService.completeOne(DLV_CORRECT_FIRST, DeliveryCarrier.CJ, trackedNo);

        assertThat(transitioned).isFalse();
        assertThat(deliveryStatus(DLV_CORRECT_FIRST)).isEqualTo("SHIPPING");
        assertThat(deliveredAt(DLV_CORRECT_FIRST)).isNull();
        assertThat(itemStatus(DLV_CORRECT_FIRST)).isEqualTo("SHIPPING");
        assertThat(autoCompleteAuditCount(DLV_CORRECT_FIRST)).isZero();
    }

    @Test
    @DisplayName("R3 수동 배송완료와 자동 배송완료 동시 → 한쪽만 전이 · 다른 쪽 422/skip · 품목 전이 1회(delivered_at 1개)")
    void manualAndAutoCompleteRaceTransitionsOnce() throws Exception {
        String trackedNo = trackingNo(DLV_BOTH_COMPLETE);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> manual = () -> {
            ready.countDown();
            awaitQuietly(start, RACE_TIMEOUT_SECONDS);
            try {
                deliveryService.markDeliveredByAdmin(DLV_BOTH_COMPLETE);
                return "MANUAL_OK";
            } catch (RuntimeException exception) {
                return exception.getClass().getSimpleName();
            }
        };
        Callable<String> auto = () -> {
            ready.countDown();
            awaitQuietly(start, RACE_TIMEOUT_SECONDS);
            try {
                return deliveryAutoCompleteService.completeOne(DLV_BOTH_COMPLETE, DeliveryCarrier.CJ, trackedNo)
                        ? "AUTO_OK" : "AUTO_SKIPPED";
            } catch (RuntimeException exception) {
                return exception.getClass().getSimpleName();
            }
        };

        Future<String> manualResult = pool.submit(manual);
        Future<String> autoResult = pool.submit(auto);
        awaitQuietly(ready, RACE_TIMEOUT_SECONDS);
        start.countDown();
        String manualOutcome = manualResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String autoOutcome = autoResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 정확히 한쪽만 전이한다(먼저 락을 잡은 쪽). 늦은 쪽은 상태 재확인에 걸려 422(수동) 또는 skip(자동)이다.
        boolean manualWon = "MANUAL_OK".equals(manualOutcome);
        boolean autoWon = "AUTO_OK".equals(autoOutcome);
        assertThat(manualWon ^ autoWon).as("정확히 한쪽만 전이: manual=%s auto=%s", manualOutcome, autoOutcome).isTrue();
        if (manualWon) {
            assertThat(autoOutcome).isEqualTo("AUTO_SKIPPED");
        } else {
            assertThat(manualOutcome).isEqualTo("DeliveryInvalidStateException");
        }

        assertThat(deliveryStatus(DLV_BOTH_COMPLETE)).isEqualTo("DELIVERED");
        assertThat(deliveredAt(DLV_BOTH_COMPLETE)).isNotNull();
        assertThat(itemStatus(DLV_BOTH_COMPLETE)).isEqualTo("DELIVERED");
        // 자동이 이겼을 때만 SYSTEM 감사 1행(수동 경로는 감사 없음) — 어느 쪽이든 2행이 되지 않는다.
        assertThat(autoCompleteAuditCount(DLV_BOTH_COMPLETE)).isEqualTo(autoWon ? 1L : 0L);
    }

    // ---------- seed·helpers ----------

    private static void awaitQuietly(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("래치 대기 중 인터럽트", exception);
        }
    }

    private void seed(Runnable seedingWork) {
        tx.executeWithoutResult(status -> {
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
                USER_ID, pid("usr_", "DLRUSR"), "dlr@example.test", "락경합구매자", "010-7777-8888");
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '락경합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "DLRSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '락경합상품', 'SALE', 13000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "DLRPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCDLR', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "DLRVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedOrderWithItem(long id) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'SHIPPING', ?, 0, 0, NOW(6), NOW(6))",
                id, pid("ord_", "DLRORD" + id), USER_ID, "ORDDLR" + id, ITEM_PRICE);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'SHIPPING', NOW(6), NOW(6), '락경합 상품', 1000)",
                id, pid("oit_", "DLROIT" + id), id, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE);
    }

    /** 발송(OUTBOUND)·배송중(SHIPPING) 배송 1건(id = order_item_id·발송 5일 전). */
    private void seedDelivery(long id) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, "
                        + "shipped_at, delivered_at, claim_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, 'SHIPPING', NOW(6) - INTERVAL 5 DAY, NULL, NULL, NOW(6), NOW(6))",
                id, publicId(id), id, "DLR-TRACK-" + id);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'DELIVERY' AND target_id BETWEEN ? AND ?", BASE + 1, BASE + 3);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id BETWEEN ? AND ?", BASE + 1, BASE + 3);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", BASE + 1, BASE + 3);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", BASE + 1, BASE + 3);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String deliveryStatus(long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery WHERE id = ?", String.class, deliveryId);
    }

    private LocalDateTime deliveredAt(long deliveryId) {
        return jdbc.queryForObject("SELECT delivered_at FROM delivery WHERE id = ?", LocalDateTime.class, deliveryId);
    }

    private String trackingNo(long deliveryId) {
        return jdbc.queryForObject("SELECT tracking_no FROM delivery WHERE id = ?", String.class, deliveryId);
    }

    private String itemStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, orderItemId);
    }

    /** 자동 배송완료가 남기는 SYSTEM 감사 행 수(송장 정정 감사는 actor_role=ADMIN이라 세지 않는다). */
    private long autoCompleteAuditCount(long deliveryId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'DELIVERY' AND target_id = ? AND actor_role = 'SYSTEM'",
                Long.class, deliveryId);
        return count == null ? 0L : count;
    }

    private static String publicId(long deliveryId) {
        return pid("dlv_", "DLRDLV" + deliveryId);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

}

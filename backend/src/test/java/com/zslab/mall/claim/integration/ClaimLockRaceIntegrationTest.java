package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
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
 * 클레임 행 락 경합 통합 테스트(Track 101-A 외부 검토 반영·실 MariaDB). {@code Claim}에는 {@code @Version}이 있어 두 트랜잭션이
 * 같은 행을 바꾸면 늦은 쪽이 낙관 락 실패로 걸러지지만, 그 실패는 <b>커밋 시점</b>에야 드러난다 — 그 전까지 두 요청이 모두
 * "취소 가능"으로 판정하고 각자 {@code ClaimRejected}를 발행하면 품목 원복·알림이 두 번 일어날 수 있고, 회수 송장은
 * {@code delivery}에 유니크 제약이 없어 RETURN Delivery가 2건 생길 수 있다.
 * 상태를 읽고 그 판정으로 전이까지 가는 구매자·관리자 진입점이 클레임 행을 먼저 잠그는지를 실제 스레드 2개로 확인한다.
 *
 * <p>클래스에 {@code @Transactional}을 두지 않는다(행 락·실제 커밋·AFTER_COMMIT 핸들러 관찰).
 * {@code DeliveryLockRaceIntegrationTest} 2스레드 + 래치 패턴을 그대로 쓴다.
 */
@TestPropertySource(properties = {
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class ClaimLockRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9802L;
    private static final long SELLER_ID = 9802L;
    private static final long PRODUCT_ID = 9802L;
    private static final long VARIANT_ID = 9802L;
    private static final long DUMMY_FK_ID = 9802L;
    private static final long ITEM_PRICE = 11_000L;

    /** 시드 주문·품목·클레임 id = BASE + n(셋이 같은 값을 쓴다). */
    private static final long BASE = 9810L;
    private static final long CLM_CANCEL_TWICE = BASE + 1;     // C1 취소 2건 동시
    private static final long CLM_CANCEL_VS_APPROVE = BASE + 2; // C2 취소 vs 관리자 승인
    private static final long CLM_RETURN_SHIPMENT = BASE + 3;   // C3 회수 송장 구매자 vs 관리자

    private static final String ADMIN_ROLE = "ADMIN";
    private static final long ADMIN_ACTOR_ID = 9802L;
    private static final int RACE_TIMEOUT_SECONDS = 30;

    @MockitoBean
    private SmsSender smsSender;

    @Autowired
    private ClaimService claimService;
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
            seedOrderWithItem(CLM_CANCEL_TWICE);
            seedClaim(CLM_CANCEL_TWICE, "REQUESTED");
            seedOrderWithItem(CLM_CANCEL_VS_APPROVE);
            seedClaim(CLM_CANCEL_VS_APPROVE, "REQUESTED");
            seedOrderWithItem(CLM_RETURN_SHIPMENT);
            seedClaim(CLM_RETURN_SHIPMENT, "APPROVED");
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("C1 같은 클레임에 구매자 취소 2건 동시 → 한쪽만 성공·다른 쪽 상태 충돌 · 최종 REJECTED 1회 · 품목 원복 1회 · 취소 알림 1건")
    void cancelTwiceConcurrently_appliesOnce() throws Exception {
        String claimPid = claimPublicId(CLM_CANCEL_TWICE);

        String[] outcomes = race(
                () -> cancelOutcome(claimPid),
                () -> cancelOutcome(claimPid));

        long okCount = countOk(outcomes);
        assertThat(okCount).as("정확히 한쪽만 성공: %s / %s", outcomes[0], outcomes[1]).isEqualTo(1L);
        assertThat(otherOutcome(outcomes)).isEqualTo("ClaimInvalidStateException");

        assertThat(claimStatus(CLM_CANCEL_TWICE)).isEqualTo("REJECTED");
        assertThat(rejectReasonCode(CLM_CANCEL_TWICE)).isEqualTo("BUYER_WITHDRAWN");
        // ClaimRejected 부수효과가 두 번 일어나지 않았다: 품목은 스냅샷(DELIVERED)으로 한 번만 돌아가고 취소 알림도 1건이다.
        assertThat(itemStatus(CLM_CANCEL_TWICE)).isEqualTo("DELIVERED");
        assertThat(smsCount(CLM_CANCEL_TWICE, "TPL_CLAIM_CANCELLED")).isEqualTo(1L);
    }

    @Test
    @DisplayName("C2 구매자 취소와 관리자 승인 동시 → 정확히 한쪽만 성공 · 최종 상태는 이긴 쪽과 일치")
    void cancelAndApproveConcurrently_exactlyOneWins() throws Exception {
        String claimPid = claimPublicId(CLM_CANCEL_VS_APPROVE);

        String[] outcomes = race(
                () -> cancelOutcome(claimPid),
                () -> {
                    try {
                        claimService.approveByAdmin(CLM_CANCEL_VS_APPROVE, LocalDateTime.now(), null,
                                AuditContext.of(ADMIN_ACTOR_ID, ADMIN_ROLE));
                        return "OK";
                    } catch (RuntimeException exception) {
                        return exception.getClass().getSimpleName();
                    }
                });

        String cancelOutcome = outcomes[0];
        String approveOutcome = outcomes[1];
        assertThat(countOk(outcomes)).as("정확히 한쪽만 성공: cancel=%s approve=%s", cancelOutcome, approveOutcome).isEqualTo(1L);

        if ("OK".equals(cancelOutcome)) {
            assertThat(claimStatus(CLM_CANCEL_VS_APPROVE)).isEqualTo("REJECTED");
            assertThat(rejectReasonCode(CLM_CANCEL_VS_APPROVE)).isEqualTo("BUYER_WITHDRAWN");
            assertThat(itemStatus(CLM_CANCEL_VS_APPROVE)).isEqualTo("DELIVERED");
        } else {
            assertThat(claimStatus(CLM_CANCEL_VS_APPROVE)).isEqualTo("APPROVED");
            assertThat(itemStatus(CLM_CANCEL_VS_APPROVE)).isEqualTo("RETURN_REQUESTED");
        }
    }

    @Test
    @DisplayName("C3 같은 클레임에 회수 송장 등록(구매자·관리자) 동시 → RETURN 배송 1건 · 늦은 쪽 422")
    void registerReturnShipmentConcurrently_createsOneDelivery() throws Exception {
        String claimPid = claimPublicId(CLM_RETURN_SHIPMENT);

        String[] outcomes = race(
                () -> {
                    try {
                        claimService.registerReturnShipmentByBuyer(claimPid, USER_ID, DeliveryCarrier.CJ, "RACE-BUYER-0001");
                        return "OK";
                    } catch (RuntimeException exception) {
                        return exception.getClass().getSimpleName();
                    }
                },
                () -> {
                    try {
                        claimService.registerReturnShipmentByAdmin(claimPid, DeliveryCarrier.HANJIN, "RACE-ADMIN-0001",
                                AuditContext.of(ADMIN_ACTOR_ID, ADMIN_ROLE));
                        return "OK";
                    } catch (RuntimeException exception) {
                        return exception.getClass().getSimpleName();
                    }
                });

        assertThat(countOk(outcomes)).as("정확히 한쪽만 성공: buyer=%s admin=%s", outcomes[0], outcomes[1]).isEqualTo(1L);
        assertThat(otherOutcome(outcomes)).isEqualTo("ClaimInvalidStateException");
        assertThat(returnDeliveryCount(CLM_RETURN_SHIPMENT)).isEqualTo(1L);
    }

    // ---------- 경합 실행 ----------

    /** 두 작업을 래치로 동시에 풀어 결과 문자열 2개를 돌려준다(DeliveryLockRaceIntegrationTest R3 패턴). */
    private String[] race(Callable<String> first, Callable<String> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> firstResult = pool.submit(gate(first, ready, start));
            Future<String> secondResult = pool.submit(gate(second, ready, start));
            awaitQuietly(ready);
            start.countDown();
            return new String[] {
                    firstResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            };
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<String> gate(Callable<String> work, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            awaitQuietly(start);
            return work.call();
        };
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("경합 래치 대기 중 인터럽트", exception);
        }
    }

    private String cancelOutcome(String claimPid) {
        try {
            claimService.cancelByBuyer(claimPid, USER_ID, LocalDateTime.now());
            return "OK";
        } catch (RuntimeException exception) {
            return exception.getClass().getSimpleName();
        }
    }

    private long countOk(String[] outcomes) {
        long count = 0;
        for (String outcome : outcomes) {
            if ("OK".equals(outcome)) {
                count++;
            }
        }
        return count;
    }

    /** 성공하지 않은 쪽의 결과(성공이 정확히 1건임을 먼저 단언한 뒤 쓴다). */
    private String otherOutcome(String[] outcomes) {
        return "OK".equals(outcomes[0]) ? outcomes[1] : outcomes[0];
    }

    // ---------- seed·helpers ----------
    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

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

    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "CLRUSR"), "clr@example.test", "클레임경합구매자", "010-5555-6666");
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '경합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "CLRSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '경합상품', 'SALE', 11000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "CLRPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCCLR', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "CLRVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    /** 반품 요청 중인 주문·품목 1건(item_status=RETURN_REQUESTED·원복 스냅샷은 DELIVERED). */
    private void seedOrderWithItem(long id) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'DELIVERED', ?, 0, 0, NOW(6), NOW(6))",
                id, pid("ord_", "CLRORD" + id), USER_ID, "ORDCLR" + id, ITEM_PRICE);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'RETURN_REQUESTED', NOW(6), NOW(6), '경합 상품', 1000)",
                id, pid("oit_", "CLROIT" + id), id, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE);
    }

    private void seedClaim(long id, String status) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                        + "requested_at, previous_order_item_status, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', ?, ?, NOW(6), 'DELIVERED', 0, NOW(6), NOW(6))",
                id, claimPublicId(id), id, status, USER_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type IN ('CLAIM', 'DELIVERY') AND target_id BETWEEN ? AND ?",
                        BASE + 1, BASE + 3);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id BETWEEN ? AND ?", BASE + 1, BASE + 3);
                jdbc.update("DELETE FROM claim WHERE order_item_id BETWEEN ? AND ?", BASE + 1, BASE + 3);
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

    private String claimStatus(long claimId) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId);
    }

    private String rejectReasonCode(long claimId) {
        return jdbc.queryForObject("SELECT reject_reason_code FROM claim WHERE id = ?", String.class, claimId);
    }

    private String itemStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, orderItemId);
    }

    private long returnDeliveryCount(long claimId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery WHERE claim_id = ? AND direction = 'RETURN'", Long.class, claimId);
        return count == null ? 0L : count;
    }

    private long smsCount(long claimId, String templateCode) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE target_type = 'CLAIM' "
                + "AND target_id = ? AND template_code = ?", Long.class, claimId, templateCode);
        return count == null ? 0L : count;
    }

    private static String claimPublicId(long claimId) {
        return pid("clm_", "CLRCLM" + claimId);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

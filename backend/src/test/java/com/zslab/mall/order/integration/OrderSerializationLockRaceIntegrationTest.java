package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.order.service.BuyerOrderConfirmService;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.service.AdminPaymentCommandService;
import com.zslab.mall.refund.enums.RefundCallbackStatus;
import com.zslab.mall.refund.service.RefundService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 단위 직렬화 경합 통합 테스트(Track 104-1 D-215·invariants P4·P5·실 MariaDB). 주문에 속한 품목·결제·환불·클레임을 바꾸는
 * 트랜잭션이 모두 주문 행 락을 먼저 잡는지, 락을 기다린 쪽이 락 뒤에 최신 커밋을 보고 주문 상태를 재계산하는지를 순서를 고정한
 * 스레드 2개로 확인한다.
 *
 * <p><b>순서 고정</b>: 먼저 가는 쪽을 주문 상태 재계산({@code OrderService.recalculateStatus}) 진입에서 멈춘다 — 품목 전이 직후라
 * 주문 락을 쥔 채다. 멈추는 것은 처음 진입한 호출 하나뿐이다(뒤 작업의 재계산은 그대로 통과). 뒤 작업이 막혔는지는 앞 쪽이 래치로
 * 락을 쥔 동안 뒤 Future가 {@link #BLOCK_PROBE_MILLIS} 안에 끝나지 않는지로 본다(ClaimLockRace·DeliveryLockRace와 같은 래치 방식).
 * 모든 대기는 {@link #RACE_TIMEOUT_SECONDS} 타임아웃(InnoDB lock wait 기본 50초보다 짧음)이라 교착·무한 대기면 실패한다.
 * 단언은 커밋된 최종 상태 → 작업 결과 → 대기 여부 순이다(틀린 상태가 있으면 그것이 먼저 드러나게).
 *
 * <p><b>시드</b>: 주문 1건(결제 PAID 20,000) = 품목 A(DELIVERED·원 발송 1일 전 배송완료) + 품목 B(시나리오별 반품 진행 상태)·
 * B의 반품 클레임·환불. 클래스에 {@code @Transactional}을 두지 않는다(행 락·실제 커밋 관찰).
 */
@TestPropertySource(properties = {
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class OrderSerializationLockRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 6201L;
    private static final long SELLER_ID = 6201L;
    private static final long PRODUCT_ID = 6201L;
    private static final long VARIANT_ID = 6201L;
    private static final long DUMMY_FK_ID = 6201L;
    private static final long ADMIN_ACTOR_ID = 6209L;
    private static final long ITEM_PRICE = 10_000L;
    private static final long PAYMENT_AMOUNT = ITEM_PRICE * 2;

    private static final long ORDER_ID = 6211L;
    private static final long ITEM_A = 6211L;
    private static final long ITEM_B = 6212L;
    private static final long PAYMENT_ID = 6211L;
    private static final long CLAIM_B = 6212L;
    private static final long REFUND_B = 6212L;
    private static final long DELIVERY_A = 6211L;

    private static final int RACE_TIMEOUT_SECONDS = 30;
    /** 뒤 작업이 이 시간 안에 끝나지 않으면(isDone false) 앞 트랜잭션의 락에 막힌 것으로 본다. */
    private static final int BLOCK_PROBE_MILLIS = 2_000;
    private static final int DELIVERED_DAYS_AGO = 1;
    private static final String PG_REFUND_ID = "osl_rfn_0001";

    @MockitoBean
    private SmsSender smsSender;
    @MockitoSpyBean
    private OrderService orderService;

    @Autowired
    private BuyerOrderConfirmService buyerOrderConfirmService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private AdminPaymentCommandService adminPaymentCommandService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private final AtomicBoolean holdFirstRecalculation = new AtomicBoolean(false);
    private CountDownLatch firstHolding;
    private CountDownLatch releaseFirst;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        doNothing().when(smsSender).send(any(), any());
        firstHolding = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        // 정지점: 처음 진입한 재계산 1건만 멈춘다(주문 락·품목 전이를 쥔 채·커밋 전).
        doAnswer(invocation -> {
            if (holdFirstRecalculation.compareAndSet(true, false)) {
                firstHolding.countDown();
                awaitQuietly(releaseFirst);
            }
            return invocation.callRealMethod();
        }).when(orderService).recalculateStatus(any());
        cleanup();
    }

    @AfterEach
    void tearDown() {
        releaseFirst.countDown();
        cleanup();
    }

    @Test
    @DisplayName("S1 구매확정이 주문 락을 쥔 동안 형제 품목 환불 완료 대기 → 락 뒤 최신 품목으로 재계산 · 주문 CONFIRMED")
    void confirmHoldsOrderLock_siblingRefundCompletionRecalculatesWithLatestItems() throws Exception {
        seed("RETURN_REQUESTED", "APPROVED", "PENDING", ITEM_PRICE);

        RaceResult result = race(this::confirmItemAOutcome, this::refundCompletionOutcome);

        assertThat(orderStatus() + "/" + itemStatus(ITEM_A) + "/" + itemStatus(ITEM_B)).isEqualTo("CONFIRMED/CONFIRMED/RETURNED");
        assertThat(result.firstOutcome()).isEqualTo("OK");
        assertThat(result.secondOutcome()).isEqualTo("OK");
        assertThat(result.secondBlocked()).as("환불 완료가 구매확정의 주문 락에 막혀 대기").isTrue();
    }

    @Test
    @DisplayName("S2 환불 완료 체인이 주문 락을 쥔 동안 형제 품목 반품 요청 대기 → 교착 없이 양쪽 종료 · 주문 PAID")
    void refundChainHoldsOrderLock_siblingReturnRequestWaitsWithoutDeadlock() throws Exception {
        seed("RETURN_REQUESTED", "APPROVED", "PENDING", ITEM_PRICE);

        RaceResult result = race(this::refundCompletionOutcome, this::returnRequestItemAOutcome);

        assertThat(orderStatus() + "/" + itemStatus(ITEM_A) + "/" + itemStatus(ITEM_B)).isEqualTo("PAID/RETURN_REQUESTED/RETURNED");
        assertThat(result.firstOutcome()).isEqualTo("OK");
        assertThat(result.secondOutcome()).isEqualTo("OK");
        assertThat(result.secondBlocked()).as("반품 요청이 환불 완료 체인의 주문 락에 막혀 대기").isTrue();
    }

    @Test
    @DisplayName("S3 구매확정이 주문 락을 쥔 동안 관리자 결제 취소 대기 → 직렬화 · 결제 CANCELLED·품목 CONFIRMED·주문 CONFIRMED")
    void confirmHoldsOrderLock_adminPaymentCancelWaits() throws Exception {
        // 형제 품목 반품 완료 + 결제액 전액 환불 완료(관리자 수동 취소가 전이하는 유일한 조건).
        seed("RETURNED", "COMPLETED", "COMPLETED", PAYMENT_AMOUNT);

        RaceResult result = race(this::confirmItemAOutcome, this::adminPaymentCancelOutcome);

        // 결제 CANCELLED + 품목 CONFIRMED 조합의 옳고 그름은 Track 104-4(구매확정 가드 재정의) 소관 — 여기서는 직렬화·일관만 본다.
        assertThat(paymentStatus() + "/" + itemStatus(ITEM_A) + "/" + orderStatus()).isEqualTo("CANCELLED/CONFIRMED/CONFIRMED");
        assertThat(result.firstOutcome()).isEqualTo("OK");
        assertThat(result.secondOutcome()).isEqualTo("OK");
        assertThat(result.secondBlocked()).as("관리자 결제 취소가 구매확정의 주문 락에 막혀 대기").isTrue();
    }

    // ---------- 경합 실행 ----------

    private record RaceResult(String firstOutcome, String secondOutcome, boolean secondBlocked) {
    }

    /** 앞 작업을 재계산 정지점에 세운 뒤 뒤 작업을 보내고, 대기 여부를 관찰한 다음 앞 작업을 풀어 두 결과를 모은다. */
    private RaceResult race(Callable<String> first, Callable<String> second) throws Exception {
        holdFirstRecalculation.set(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstResult = pool.submit(first);
            assertThat(firstHolding.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("앞 작업이 정지점에 도달").isTrue();
            Future<String> secondResult = pool.submit(second);
            boolean secondBlocked = isBlocked(secondResult);
            releaseFirst.countDown();
            String firstOutcome = firstResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String secondOutcome = secondResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new RaceResult(firstOutcome, secondOutcome, secondBlocked);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 앞 트랜잭션이 락을 쥔 동안 뒤 작업이 {@link #BLOCK_PROBE_MILLIS} 안에 끝나지 않으면 막힌 것으로 본다(래치 해제 전 관찰). */
    private boolean isBlocked(Future<String> future) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(BLOCK_PROBE_MILLIS);
        return !future.isDone();
    }

    private String confirmItemAOutcome() {
        return outcome(() -> buyerOrderConfirmService.confirmPurchase(USER_ID, pid("ord_", "OSLORD"), pid("oit_", "OSLOITA")));
    }

    private String refundCompletionOutcome() {
        return outcome(() -> refundService.handleCallback(PG_REFUND_ID, RefundCallbackStatus.SUCCESS, null));
    }

    private String returnRequestItemAOutcome() {
        return outcome(() -> claimService.request(new ClaimRequestCommand(pid("oit_", "OSLOITA"), ClaimType.RETURN,
                ClaimReasonCode.PRODUCT_DEFECT, null, USER_ID, LocalDateTime.now())));
    }

    private String adminPaymentCancelOutcome() {
        return outcome(() -> adminPaymentCommandService.markCancelled(pid("pay_", "OSLPAY"), "경합 테스트",
                AuditContext.of(ADMIN_ACTOR_ID, "ADMIN")));
    }

    private String outcome(Runnable work) {
        try {
            work.run();
            return "OK";
        } catch (RuntimeException exception) {
            return exception.getClass().getSimpleName();
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("경합 래치 대기 중 인터럽트", exception);
        }
    }

    // ---------- seed·helpers ----------
    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** 품목 A는 DELIVERED 고정, 품목 B·B의 반품 클레임·환불 상태와 환불 금액은 시나리오별로 받는다. */
    private void seed(String itemBStatus, String claimBStatus, String refundBStatus, long refundAmount) {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "OSLUSR"), "osl@example.test", "직렬화구매자", "010-6200-6200");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '직렬화셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "OSLSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, '직렬화상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "OSLPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCOSL', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "OSLVAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "OSLORD"), USER_ID, "ORDOSL" + ORDER_ID, PAYMENT_AMOUNT);
                seedItem(ITEM_A, "OSLOITA", "DELIVERED");
                seedItem(ITEM_B, "OSLOITB", itemBStatus);
                jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, "
                                + "payment_attempt_key, paid_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_osl_0001', 'pat_osl_0001', NOW(6), NOW(6), NOW(6))",
                        PAYMENT_ID, pid("pay_", "OSLPAY"), ORDER_ID, PAYMENT_AMOUNT);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                                + "requested_at, previous_order_item_status, version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', ?, ?, NOW(6), 'DELIVERED', 0, NOW(6), NOW(6))",
                        CLAIM_B, pid("clm_", "OSLCLM"), ITEM_B, claimBStatus, USER_ID);
                jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, refunded_at, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, "
                                + "CASE WHEN ? = 'COMPLETED' THEN NOW(6) ELSE NULL END, NOW(6), NOW(6))",
                        REFUND_B, pid("rfn_", "OSLRFN"), CLAIM_B, PAYMENT_ID, refundAmount, refundBStatus, PG_REFUND_ID,
                        refundBStatus);
                jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, "
                                + "delivered_at, claim_id, created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', 'OSL-TRACK-1', "
                                + "'DELIVERED', NOW(6) - INTERVAL ? DAY, NOW(6) - INTERVAL ? DAY, NULL, NOW(6), NOW(6))",
                        DELIVERY_A, pid("dlv_", "OSLDLV"), ITEM_A, DELIVERED_DAYS_AGO + 1, DELIVERED_DAYS_AGO);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedItem(long id, String tag, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '직렬화 상품', 1000)",
                id, pid("oit_", tag), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'PAYMENT' AND target_id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id IN (?, ?)", ITEM_A, ITEM_B);
                jdbc.update("DELETE FROM refund WHERE payment_id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM claim WHERE order_item_id IN (?, ?)", ITEM_A, ITEM_B);
                jdbc.update("DELETE FROM payment WHERE order_id = ?", ORDER_ID);
                jdbc.update("DELETE FROM order_item WHERE order_id = ?", ORDER_ID);
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

    private String paymentStatus() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT_ID);
    }

    private String itemStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, orderItemId);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

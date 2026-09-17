package com.zslab.mall.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.enums.CallbackType;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import com.zslab.mall.payment.service.ExpirePaymentService;
import com.zslab.mall.payment.service.PaymentService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 콜백 Payment 행 직렬화 동시성 통합 테스트(검수 1단계 D-173·실 MariaDB·Flyway). SUCCESS 콜백 vs 결제 만료(expireOne)·
 * SUCCESS vs FAILURE 콜백을 실 스레드로 동시 실행해 결과가 둘 중 하나로 결정되고(교착·락 예외 0), 같은 variant를 예약한
 * 타 주문(B)의 예약분이 어느 결과에서도 차감·해제되지 않으며, 재고 합계(available = on_hand − reserved)가 유지됨을 검증한다.
 *
 * <p><b>시드</b>: 주문 A(콜백 대상·수량 1)·주문 B(같은 variant 예약 1)·inventory on_hand 10·reserved 2·available 8.
 * 결과 판정: PAID면 on_hand 9·reserved 1·history 1, 종료(EXPIRED/FAILED)면 on_hand 10·reserved 1·history 0 — 어느 쪽이든
 * reserved 1 = B의 예약분이다. 만료 시각은 DB NOW(6)(UTC) 대신 JVM LocalDateTime 바인딩으로 세팅한다(STEP 248 트랩).
 *
 * <p><b>스케줄러 자동 발화 차단</b>: 만료·자동취소·환불 복구 배치를 끈다(InventoryConcurrencyIntegrationTest 정합).
 */
@TestPropertySource(properties = {
        "zslab.payment.expiry.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false"})
class PaymentCallbackConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9791L;
    private static final long SELLER_ID = 9791L;
    private static final long PRODUCT_ID = 9791L;
    private static final long VARIANT_ID = 9791L;
    private static final long INVENTORY_ID = 9791L;
    private static final long ORDER_A_ID = 9791L;
    private static final long ORDER_A_ITEM_ID = 9791L;
    private static final long ORDER_B_ID = 9792L;
    private static final long ORDER_B_ITEM_ID = 9792L;
    private static final long PAYMENT_ID = 9791L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9791L;
    private static final long AMOUNT = 10_000L;
    private static final String ATTEMPT_KEY = "pat_D173CALLBACK000000000000";
    private static final String PG_PROVIDER = "MOCK_PG";
    private static final int ROUNDS = 10;
    private static final int ON_HAND = 10;
    private static final int RESERVED_A_PLUS_B = 2;
    private static final int RESERVED_B_ONLY = 1;
    private static final long WORKER_TIMEOUT_SECONDS = 30L;

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ExpirePaymentService expirePaymentService;
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
    @DisplayName("T1 SUCCESS 콜백 vs 만료(expireOne) 동시 10라운드: 결과 PAID/EXPIRED 중 하나·교착 0·타 주문 B 예약 불변·재고 합계 일치")
    void successCallback_vsExpire_resolvesToOneOutcome() throws Exception {
        int paidRounds = 0;
        int expiredRounds = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            cleanup();
            seedGraph(LocalDateTime.now().minusMinutes(1));   // 이미 만료된 PENDING 결제
            String pgTid = "tid_d173_t1_" + round;

            List<Throwable> failures = runConcurrently(List.of(
                    () -> { paymentService.handleCallback(successCommand(pgTid)); return null; },
                    () -> { expirePaymentService.expireOne(PAYMENT_ID); return null; }));

            // 허용 실패 = 늦은 승인 422(InvalidCallbackException)뿐. 교착·락 타임아웃·불변식 예외는 실패.
            assertThat(failures).allMatch(InvalidCallbackException.class::isInstance);

            if ("PAID".equals(paymentStatus())) {
                paidRounds++;
                assertThat(failures).isEmpty();
                assertPaidOutcome();
            } else {
                expiredRounds++;
                assertThat(paymentStatus()).isEqualTo("EXPIRED");
                assertThat(failures).hasSize(1);
                assertTerminatedOutcome();
            }
            assertCommonInvariants();
        }
        assertThat(paidRounds + expiredRounds).isEqualTo(ROUNDS);
    }

    @Test
    @DisplayName("T2 SUCCESS vs FAILURE 콜백 동시 10라운드: Payment 최종 상태 1개(PAID/FAILED)·재고 처리 1회·타 주문 B 예약 불변")
    void successCallback_vsFailureCallback_resolvesToOneOutcome() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            cleanup();
            seedGraph(LocalDateTime.now().plusMinutes(30));   // 미만료 PENDING
            String pgTid = "tid_d173_t2_" + round;

            List<Throwable> failures = runConcurrently(List.of(
                    () -> { paymentService.handleCallback(successCommand(pgTid)); return null; },
                    () -> { paymentService.handleCallback(failureCommand()); return null; }));

            assertThat(failures).allMatch(InvalidCallbackException.class::isInstance);

            if ("PAID".equals(paymentStatus())) {
                assertThat(failures).isEmpty();          // 후행 FAILURE는 비PENDING NO-OP(200)
                assertPaidOutcome();
            } else {
                assertThat(paymentStatus()).isEqualTo("FAILED");
                assertThat(failures).hasSize(1);          // 후행 SUCCESS × FAILED REJECT
                assertTerminatedOutcome();
            }
            assertCommonInvariants();
        }
    }

    @Test
    @DisplayName("T3 늦은 승인: 만료 종료 후 SUCCESS → InvalidCallbackException(422)·Payment EXPIRED 유지·재고 불변(B 예약 유지)")
    void lateApproval_afterExpiry_rejectsWithoutTouchingInventory() {
        seedGraph(LocalDateTime.now().minusMinutes(1));
        expirePaymentService.expireOne(PAYMENT_ID);
        assertThat(paymentStatus()).isEqualTo("EXPIRED");
        assertTerminatedOutcome();

        assertThatThrownBy(() -> paymentService.handleCallback(successCommand("tid_d173_t3")))
                .isInstanceOf(InvalidCallbackException.class);

        assertThat(paymentStatus()).isEqualTo("EXPIRED");
        assertTerminatedOutcome();
        assertCommonInvariants();
    }

    // ---------- 결과 판정 ----------

    private void assertPaidOutcome() {
        assertThat(orderStatus(ORDER_A_ID)).isEqualTo("PAID");
        assertThat(onHand()).isEqualTo(ON_HAND - 1);
        assertThat(reserved()).isEqualTo(RESERVED_B_ONLY);
        assertThat(historyCount()).isEqualTo(1);
    }

    private void assertTerminatedOutcome() {
        assertThat(orderStatus(ORDER_A_ID)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(onHand()).isEqualTo(ON_HAND);
        assertThat(reserved()).isEqualTo(RESERVED_B_ONLY);
        assertThat(historyCount()).isZero();
    }

    /** 어느 결과든 타 주문 B는 PENDING_PAYMENT·예약 1 유지, available = on_hand − reserved. */
    private void assertCommonInvariants() {
        assertThat(orderStatus(ORDER_B_ID)).isEqualTo("PENDING_PAYMENT");
        assertThat(available()).isEqualTo(onHand() - reserved());
    }

    // ---------- 동시 실행 ----------

    private List<Throwable> runConcurrently(List<Callable<Void>> works) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(works.size());
        CountDownLatch ready = new CountDownLatch(works.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (Callable<Void> work : works) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return work.call();
                }));
            }
            assertThat(ready.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Void> future : futures) {
                try {
                    future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                } catch (TimeoutException exception) {
                    throw new IllegalStateException("워커 타임아웃(데드락 의심)", exception);
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    private PaymentCallbackCommand successCommand(String pgTid) {
        return new PaymentCallbackCommand(PG_PROVIDER, CallbackType.SUCCESS, ATTEMPT_KEY, pgTid, LocalDateTime.now(), Map.of());
    }

    private PaymentCallbackCommand failureCommand() {
        return new PaymentCallbackCommand(PG_PROVIDER, CallbackType.FAILURE, ATTEMPT_KEY, null, LocalDateTime.now(),
                Map.of("failureCode", "PG_FAILURE"));
    }

    // ---------- 시드·정리 (InventoryConcurrencyIntegrationTest 패턴·모든 변수 ? 바인딩·문자열 concat 없음) ----------

    private void seedGraph(LocalDateTime paymentExpiresAt) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "D173USR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, 'D173셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "D173SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'D173상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "D173PRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCD173', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "D173VAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID, ON_HAND, RESERVED_A_PLUS_B, ON_HAND - RESERVED_A_PLUS_B);
                seedOrder(ORDER_A_ID, ORDER_A_ITEM_ID, "A");
                seedOrder(ORDER_B_ID, ORDER_B_ITEM_ID, "B");
                jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, "
                                + "expires_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'CARD', ?, 'PENDING', ?, ?, NOW(6), NOW(6))",
                        PAYMENT_ID, pid("pay_", "D173PAY"), ORDER_A_ID, AMOUNT, ATTEMPT_KEY, paymentExpiresAt);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /** PENDING_PAYMENT 주문 + ORDERED 품목(수량 1·같은 variant) 1쌍을 시드한다. */
    private void seedOrder(long orderId, long orderItemId, String tag) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, ordered_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', ?, 0, 0, NOW(6), NOW(6), NOW(6))",
                orderId, pid("ord_", "D173ORD" + tag), USER_ID, "ORDD173" + orderId, AMOUNT);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'ORDERED', NOW(6), NOW(6), 'D173 상품')",
                orderItemId, pid("oit_", "D173OIT" + tag), orderId, PRODUCT_ID, VARIANT_ID, SELLER_ID, AMOUNT, AMOUNT);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM order_item WHERE id IN (?, ?)", ORDER_A_ITEM_ID, ORDER_B_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id IN (?, ?)", ORDER_A_ID, ORDER_B_ID);
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

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, orderId);
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int available() {
        return jdbc.queryForObject("SELECT quantity_available FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int historyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ?", Integer.class, INVENTORY_ID);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

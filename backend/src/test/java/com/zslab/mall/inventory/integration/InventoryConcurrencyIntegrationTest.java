package com.zslab.mall.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.checkout.command.CheckoutCommand;
import com.zslab.mall.checkout.command.CheckoutItemCommand;
import com.zslab.mall.checkout.service.CheckoutService;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.order.command.CreateOrderCommand;
import com.zslab.mall.order.command.OrderItemCommand;
import com.zslab.mall.order.command.ShippingAddressCommand;
import com.zslab.mall.order.event.OrderTerminated;
import com.zslab.mall.order.exception.OrderNotPayableException;
import com.zslab.mall.order.exception.OrderNotPayableReason;
import com.zslab.mall.order.service.OrderAutoCancelService;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재고 정합성 동시성 통합 테스트(Track 78 D-167·실 MariaDB·Flyway). 동일 주문 동시 종료(R3: 조건부 UPDATE로 OrderTerminated·release
 * 1회 보장·같은 variant 타 주문 예약 불변)와 재고 1개 동시 주문 생성(R4: 동기 예약 FOR UPDATE 직렬화·주문 1건만 생성·나머지
 * OUT_OF_STOCK·available ≥ 0)·예약 실패 시 주문 미생성(TX 롤백)을 실 스레드·실 커밋 경로로 검증한다.
 *
 * <p><b>이벤트 계수</b>: {@code @RecordApplicationEvents}는 테스트 스레드 한정이라 워커 스레드 발행을 잡지 못한다. 대신 컨텍스트에
 * 임시 {@link ApplicationListener}를 등록해 {@link OrderTerminated} payload를 세고 종료 시 제거한다.
 *
 * <p><b>스케줄러 자동 발화 차단</b>: {@code OrderAutoCancelIntegrationTest}와 동일하게 자동취소 배치를 끈다.
 */
@TestPropertySource(properties = {"zslab.order.auto-cancel.enabled=false", "zslab.refund.recovery.enabled=false"})
class InventoryConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9781L;
    private static final long SELLER_ID = 9781L;
    private static final long PRODUCT_ID = 9781L;
    private static final long VARIANT_ID = 9781L;
    private static final long INVENTORY_ID = 9781L;
    private static final long ORDER_A_ID = 9781L;
    private static final long ORDER_A_ITEM_ID = 9781L;
    private static final long ORDER_B_ID = 9782L;
    private static final long ORDER_B_ITEM_ID = 9782L;
    /** product.category_id·variant.option1_value_id NOT NULL FK 충족용 더미(FK_CHECKS=0 시드로 우회). */
    private static final long DUMMY_FK_ID = 9781L;
    private static final int QTY_PER_ORDER = 2;
    private static final int THREADS = 8;
    private static final long WORKER_TIMEOUT_SECONDS = 30L;
    private static final String PRODUCT_PID = pid("prd_", "T78PRD");
    private static final String VARIANT_PID = pid("var_", "T78VAR");

    @Autowired
    private OrderAutoCancelService orderAutoCancelService;
    @Autowired
    private CheckoutService checkoutService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ConfigurableApplicationContext applicationContext;

    private TransactionTemplate tx;
    private final AtomicInteger orderTerminatedCount = new AtomicInteger();
    private ApplicationListener<ApplicationEvent> orderTerminatedCounter;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        orderTerminatedCount.set(0);
        orderTerminatedCounter = new ApplicationListener<>() {
            @Override
            public void onApplicationEvent(ApplicationEvent event) {
                if (event instanceof PayloadApplicationEvent<?> payloadEvent
                        && payloadEvent.getPayload() instanceof OrderTerminated) {
                    orderTerminatedCount.incrementAndGet();
                }
            }
        };
        applicationContext.addApplicationListener(orderTerminatedCounter);
    }

    @AfterEach
    void tearDown() {
        // addApplicationListener는 multicaster에도 즉시 등록되므로 제거는 multicaster에서 직접 한다.
        applicationContext.getBean(
                        AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class)
                .removeApplicationListener(orderTerminatedCounter);
        cleanup();
    }

    @Test
    @DisplayName("T1 동시 종료(R3): 같은 주문 8스레드 cancelOne → OrderTerminated 1회·release 1회·같은 variant 타 주문 reserved 불변")
    void concurrentCancelOne_terminatesOnce_andReleasesOnce() throws Exception {
        seed(() -> {
            seedCatalog();
            // 주문 A(2)·B(2) 예약분 합계 4. 종료 2회 발생 시 B 예약까지 해제돼 0이 되므로 단일 해제(2 잔존)와 판별된다.
            seedInventory(10, QTY_PER_ORDER * 2, 10 - QTY_PER_ORDER * 2);
            seedOrder(ORDER_A_ID, ORDER_A_ITEM_ID, "A");
            seedOrder(ORDER_B_ID, ORDER_B_ITEM_ID, "B");
        });

        List<Throwable> failures = runConcurrently(() -> {
            orderAutoCancelService.cancelOne(ORDER_A_ID);
            return null;
        });

        assertThat(failures).isEmpty();
        assertThat(orderStatus(ORDER_A_ID)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(orderStatus(ORDER_B_ID)).isEqualTo("PENDING_PAYMENT");
        assertThat(orderTerminatedCount.get()).isEqualTo(1);
        assertThat(reserved()).isEqualTo(QTY_PER_ORDER);           // B 예약분만 잔존(A 1회 해제)
        assertThat(available()).isEqualTo(10 - QTY_PER_ORDER);
        assertThat(onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("T2 동시 주문 생성(R4): 재고 1개에 8스레드 checkout → 주문 1건만 생성·나머지 OUT_OF_STOCK·reserved 1·available 0")
    void concurrentCheckout_singleStock_onlyOneOrderCreated() throws Exception {
        seed(() -> {
            seedCatalog();
            seedInventory(1, 0, 1);
        });

        // 사전 검증(available=1)은 8스레드 전부 통과할 수 있다 — 동기 예약 FOR UPDATE가 최종 직렬화 지점이다.
        List<Throwable> failures = runConcurrently(() -> {
            checkoutService.checkout(checkoutCommand());
            return null;
        });

        assertThat(failures).hasSize(THREADS - 1)
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(OrderNotPayableException.class)
                        .extracting(exception -> ((OrderNotPayableException) exception).getReason())
                        .isEqualTo(OrderNotPayableReason.OUT_OF_STOCK));
        assertThat(orderCountByBuyer()).isEqualTo(1);        // 실패 7건은 주문 TX 롤백(잔존 0)
        assertThat(reserved()).isEqualTo(1);
        assertThat(available()).isZero();
        assertThat(onHand()).isEqualTo(1);
    }

    @Test
    @DisplayName("T3 예약 실패 시 주문 미생성(R4): available 0에 createOrder → InventoryInvariantViolationException·Order 0건·재고 불변")
    void createOrder_reserveFails_rollsBackOrder() {
        seed(() -> {
            seedCatalog();
            seedInventory(1, 1, 0);   // 타 주문이 이미 예약한 상태(사전 검증 우회·createOrder 직접 호출)
        });

        assertThatThrownBy(() -> orderService.createOrder(new CreateOrderCommand(
                USER_ID,
                List.of(new OrderItemCommand(PRODUCT_ID, VARIANT_ID, SELLER_ID, "T78상품", 1, 10000L, 10000L, 1000)),
                shipping(), 0L, 0L)))
                .isInstanceOf(InventoryInvariantViolationException.class);

        assertThat(orderCountByBuyer()).isZero();
        assertThat(reserved()).isEqualTo(1);
        assertThat(available()).isZero();
    }

    // ---------- 동시 실행 ----------

    /** 워커 THREADS개를 latch로 동시 출발시키고 각 워커의 예외를 모아 반환한다(성공 = 예외 없음). */
    private List<Throwable> runConcurrently(Callable<Void> work) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
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

    // ---------- 시드·helpers (OrderAutoCancelIntegrationTest 패턴) ----------

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
                USER_ID, pid("usr_", "T78USR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'T78셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "T78SLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'T78상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, PRODUCT_PID, SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCT78', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, VARIANT_PID, PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedInventory(int onHand, int reserved, int available) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                INVENTORY_ID, VARIANT_ID, onHand, reserved, available);
    }

    /** PENDING_PAYMENT 주문 + ORDERED 품목(QTY_PER_ORDER·같은 variant) 1쌍을 시드한다. */
    private void seedOrder(long orderId, long orderItemId, String tag) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 20000, 0, 0, NOW(6), NOW(6))",
                orderId, pid("ord_", "T78ORD" + tag), USER_ID, "ORDT78" + orderId);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 10000, 20000, 'ORDERED', NOW(6), NOW(6), '테스트 상품', 1000)",
                orderItemId, pid("oit_", "T78OIT" + tag), orderId, PRODUCT_ID, VARIANT_ID, SELLER_ID, QTY_PER_ORDER);
    }

    /** 직접주문 checkout 명령(수량 1·CARD·멱등 키 없음). 시드한 product/variant public_id로 해소된다. */
    private CheckoutCommand checkoutCommand() {
        return new CheckoutCommand(USER_ID, null,
                List.of(new CheckoutItemCommand(PRODUCT_PID, VARIANT_PID, 1)), shipping(), PaymentMethod.CARD);
    }

    private static ShippingAddressCommand shipping() {
        return new ShippingAddressCommand("홍길동", "010-1234-5678", "06236", "서울 강남대로 1", null, "101호", null);
    }

    private int orderCountByBuyer() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE buyer_id = ?", Integer.class, USER_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                // checkout이 생성한 동적 id 주문 그래프(payment·snapshot·item·order)는 buyer 기준으로 정리한다.
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM payment WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", USER_ID);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", USER_ID);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", USER_ID);
                jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", USER_ID);
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

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, orderId);
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

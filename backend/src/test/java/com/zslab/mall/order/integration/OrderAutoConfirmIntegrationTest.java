package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.SellerGrossProjection;
import com.zslab.mall.order.scheduler.OrderAutoConfirmScheduler;
import com.zslab.mall.order.service.OrderAutoConfirmService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자동 구매확정 배치 통합 테스트(Track 81-B D-171·R7·실 MariaDB·Flyway V24 인덱스). 원 발송 7일 경과 확정·7일 미만 제외·활성 클레임 제외·
 * 재발송 배송완료 무시·킬스위치·1건 실패 격리·반품 요청과의 경합·정산 집계 반영을 실제 커밋으로 검증한다.
 *
 * <p><b>스케줄러 자동 발화 차단</b>: {@code zslab.order.auto-confirm.enabled=false}로 {@code @Scheduled} 빈을 끄고 배치는
 * {@link OrderAutoConfirmScheduler}를 직접 생성해 호출한다(킬스위치 검증 겸). 클래스에 {@code @Transactional}을 두지 않는다(행 락·경합 검증).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class OrderAutoConfirmIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9402L;
    private static final long SELLER_ID = 9402L;
    private static final long PRODUCT_ID = 9402L;
    private static final long VARIANT_ID = 9402L;
    private static final long DUMMY_FK_ID = 9402L;
    private static final long ITEM_PRICE = 10_000L;
    private static final String BUYER_PHONE = "010-4444-5555";
    /** 시드 품목 6건: id = BASE + n(주문·품목·배송 id 동일 번호). */
    private static final long BASE = 9410L;
    private static final long ITEM_EXPIRED = BASE + 1;         // 원 발송 8일 경과 → 확정
    private static final long ITEM_RECENT = BASE + 2;          // 원 발송 3일 → 제외
    private static final long ITEM_ACTIVE_CLAIM = BASE + 3;    // 8일 경과 + REQUESTED 클레임 → 제외
    private static final long ITEM_RESHIPPED = BASE + 4;       // 원 발송 3일 + 재발송(claim_id) 8일 경과 → 제외
    private static final long ITEM_EXPIRED_2 = BASE + 5;       // 8일 경과 → 확정(격리 검증용)
    private static final long ITEM_RACE = BASE + 6;            // 6일 → 경합 검증용
    private static final long LINKED_CLAIM_ID = 999_998L;
    private static final int RACE_WINDOW_DAYS_AGO = 6;

    @MockitoBean
    private SmsSender smsSender;
    @MockitoSpyBean
    private OrderAutoConfirmService orderAutoConfirmService;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private OrderAutoConfirmScheduler scheduler;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        doNothing().when(smsSender).send(any(), any());
        scheduler = new OrderAutoConfirmScheduler(deliveryRepository, orderAutoConfirmService);
        cleanup();
        seed(() -> {
            seedCatalog();
            seedOrderWithItem(ITEM_EXPIRED, OrderItemStatus.DELIVERED);
            seedOutbound(ITEM_EXPIRED, 8, null);
            seedOrderWithItem(ITEM_RECENT, OrderItemStatus.DELIVERED);
            seedOutbound(ITEM_RECENT, 3, null);
            seedOrderWithItem(ITEM_ACTIVE_CLAIM, OrderItemStatus.DELIVERED);
            seedOutbound(ITEM_ACTIVE_CLAIM, 8, null);
            seedRequestedClaim(ITEM_ACTIVE_CLAIM);
            seedOrderWithItem(ITEM_RESHIPPED, OrderItemStatus.DELIVERED);
            seedOutbound(ITEM_RESHIPPED, 3, null);
            seedOutbound(ITEM_RESHIPPED, 8, LINKED_CLAIM_ID);
            seedOrderWithItem(ITEM_EXPIRED_2, OrderItemStatus.DELIVERED);
            seedOutbound(ITEM_EXPIRED_2, 8, null);
            seedOrderWithItem(ITEM_RACE, OrderItemStatus.DELIVERED);
            seedOutbound(ITEM_RACE, RACE_WINDOW_DAYS_AGO, null);
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 배치: 원 발송 8일 경과만 CONFIRMED(confirmed_at·주문 상태) / 3일·활성 클레임·재발송 8일 경과·6일은 DELIVERED 유지 / 재실행 멱등")
    void batch_confirmsOnlyExpiredOriginalOutbound() {
        scheduler.confirmBatch();

        assertThat(itemStatus(ITEM_EXPIRED)).isEqualTo("CONFIRMED");
        assertThat(confirmedAt(ITEM_EXPIRED)).isNotNull();
        assertThat(orderStatus(ITEM_EXPIRED)).isEqualTo("CONFIRMED");
        assertThat(itemStatus(ITEM_EXPIRED_2)).isEqualTo("CONFIRMED");
        assertThat(itemStatus(ITEM_RECENT)).isEqualTo("DELIVERED");
        assertThat(itemStatus(ITEM_ACTIVE_CLAIM)).isEqualTo("DELIVERED");
        assertThat(confirmedAt(ITEM_ACTIVE_CLAIM)).isNull();
        assertThat(itemStatus(ITEM_RESHIPPED)).isEqualTo("DELIVERED"); // 기한은 원 발송(claim_id NULL) 기준
        assertThat(itemStatus(ITEM_RACE)).isEqualTo("DELIVERED");

        // 후보 조회는 활성 클레임 품목도 DELIVERED면 포함하되 서비스 재확인이 skip한다(2중 가드)
        List<Long> candidates = deliveryRepository.findAutoConfirmCandidateOrderItemIds(
                DeliveryDirection.OUTBOUND, DeliveryStatus.DELIVERED,
                LocalDateTime.now().minusDays(7), OrderItemStatus.DELIVERED, PageRequest.of(0, 100));
        assertThat(candidates).containsExactly(ITEM_ACTIVE_CLAIM);
        assertThat(orderAutoConfirmService.confirmOne(ITEM_ACTIVE_CLAIM, LocalDateTime.now())).isFalse();

        // 재실행: 이미 CONFIRMED는 후보에서 빠지고 아무것도 바뀌지 않는다
        LocalDateTime firstConfirmedAt = confirmedAt(ITEM_EXPIRED);
        scheduler.confirmBatch();
        assertThat(confirmedAt(ITEM_EXPIRED)).isEqualTo(firstConfirmedAt);
    }

    @Test
    @DisplayName("T2 킬스위치: enabled=false 컨텍스트에 스케줄러 빈 없음 / 1건 실패(격리) → 나머지 정상 확정")
    void killSwitch_andPartialFailureIsolation() {
        assertThat(applicationContext.getBeanNamesForType(OrderAutoConfirmScheduler.class)).isEmpty();

        doThrow(new IllegalStateException("주입 실패")).when(orderAutoConfirmService).confirmOne(eq(ITEM_EXPIRED), any());
        scheduler.confirmBatch();

        assertThat(itemStatus(ITEM_EXPIRED)).isEqualTo("DELIVERED");
        assertThat(itemStatus(ITEM_EXPIRED_2)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("T3 정산 반영: 자동 확정 + 수동 확정(API) 품목이 같은 confirmed_at 집계(gross)에 합산된다")
    void settlement_autoAndManualConfirmAggregateSame() throws Exception {
        scheduler.confirmBatch(); // ITEM_EXPIRED·ITEM_EXPIRED_2 자동 확정

        mockMvc.perform(post("/api/v1/orders/" + orderPid(ITEM_RECENT) + "/items/" + itemPid(ITEM_RECENT) + "/confirm")
                        .headers(authHeaders.buyer(USER_ID)))
                .andExpect(status().isOk());

        LocalDateTime now = LocalDateTime.now();
        List<SellerGrossProjection> gross = orderItemRepository.aggregateGrossBySeller(
                OrderItemStatus.CONFIRMED, now.minusMinutes(5), now.plusMinutes(5));
        SellerGrossProjection sellerGross = gross.stream().filter(row -> row.getSellerId().equals(SELLER_ID)).findFirst().orElseThrow();
        assertThat(sellerGross.getGrossAmount()).isEqualTo(ITEM_PRICE * 3);
        assertThat(orderItemRepository.sumConfirmedTotalPriceByBuyerId(USER_ID, OrderItemStatus.CONFIRMED)).isEqualTo(ITEM_PRICE * 3);
    }

    @Test
    @DisplayName("T4 경합: 자동 확정(기준 시각 = 기한 직후) vs 반품 요청(기한 안) 동시 → 정확히 1건만 성공")
    void race_autoConfirmVersusReturnRequest() throws Exception {
        LocalDateTime pastDeadline = LocalDateTime.now().plusDays(2); // 6일 전 배송완료 → 지금은 기한 안, +2일은 기한 밖
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> autoConfirm = () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return orderAutoConfirmService.confirmOne(ITEM_RACE, pastDeadline) ? "CONFIRMED" : "SKIPPED";
        };
        Callable<String> returnRequest = () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                claimService.request(new ClaimRequestCommand(itemPid(ITEM_RACE), ClaimType.RETURN,
                        ClaimReasonCode.BUYER_CHANGED_MIND, null, USER_ID, LocalDateTime.now()));
                return "REQUESTED";
            } catch (ClaimInvalidStateException exception) {
                return "REJECTED";
            }
        };
        Future<String> autoResult = pool.submit(autoConfirm);
        Future<String> claimResult = pool.submit(returnRequest);
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        String autoOutcome = autoResult.get(30, TimeUnit.SECONDS);
        String claimOutcome = claimResult.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        if ("CONFIRMED".equals(autoOutcome)) {
            assertThat(claimOutcome).isEqualTo("REJECTED");
            assertThat(itemStatus(ITEM_RACE)).isEqualTo("CONFIRMED");
            assertThat(claimCount(ITEM_RACE)).isZero();
        } else {
            assertThat(autoOutcome).isEqualTo("SKIPPED");
            assertThat(claimOutcome).isEqualTo("REQUESTED");
            assertThat(itemStatus(ITEM_RACE)).isEqualTo("RETURN_REQUESTED");
            assertThat(claimCount(ITEM_RACE)).isEqualTo(1L);
        }
    }

    // ---------- seed·helpers ----------

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
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "ACFUSR"), "acf@example.test", "자동확정구매자", BUYER_PHONE);
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '자동확정셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "ACFSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '자동확정상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "ACFPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCACF', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "ACFVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    /** 주문 1건 + 품목 1건(id 동일 번호·DELIVERED 주문 상태). */
    private void seedOrderWithItem(long id, OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'DELIVERED', ?, 0, 0, NOW(6), NOW(6))",
                id, orderPid(id), USER_ID, "ORDACF" + id, ITEM_PRICE);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '자동확정 상품', 1000)",
                id, itemPid(id), id, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus.name());
    }

    /** 발송(OUTBOUND) 배송완료 Delivery(delivered_at = 지금 − daysAgo일). claimId가 있으면 클레임 연결 발송(재발송·교환). */
    private void seedOutbound(long orderItemId, int daysAgo, Long claimId) {
        long deliveryId = claimId == null ? orderItemId : orderItemId + 100;
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                        + "claim_id, created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, 'DELIVERED', "
                        + "NOW(6) - INTERVAL ? DAY - INTERVAL 1 DAY, NOW(6) - INTERVAL ? DAY, ?, NOW(6), NOW(6))",
                deliveryId, pid("dlv_", "ACFDLV" + deliveryId), orderItemId, "ACF-TRACK-" + deliveryId, daysAgo, daysAgo, claimId);
    }

    /** 품목 상태와 무관한 REQUESTED 클레임 행(활성 클레임 가드 검증용·시드라 상태 정합은 요구하지 않음). */
    private void seedRequestedClaim(long orderItemId) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, status, reason_code, requested_by, requested_at, "
                        + "previous_order_item_status, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'RETURN', 'REQUESTED', 'PRODUCT_DEFECT', ?, NOW(6), 'DELIVERED', 0, NOW(6), NOW(6))",
                orderItemId, pid("clm_", "ACFCLM"), orderItemId, USER_ID);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM delivery WHERE order_item_id BETWEEN ? AND ?", BASE + 1, BASE + 6);
                jdbc.update("DELETE FROM claim WHERE order_item_id BETWEEN ? AND ?", BASE + 1, BASE + 6);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", BASE + 1, BASE + 6);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", BASE + 1, BASE + 6);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String itemStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, orderItemId);
    }

    private LocalDateTime confirmedAt(long orderItemId) {
        return jdbc.queryForObject("SELECT confirmed_at FROM order_item WHERE id = ?", LocalDateTime.class, orderItemId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, orderId);
    }

    private long claimCount(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM claim WHERE order_item_id = ?", Long.class, orderItemId);
    }

    private static String orderPid(long id) {
        return pid("ord_", "ACFORD" + id);
    }

    private static String itemPid(long id) {
        return pid("oit_", "ACFOIT" + id);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

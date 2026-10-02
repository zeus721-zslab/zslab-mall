package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.support.AbstractIntegrationTest;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 클레임 승인의 동시 진입(자체 평점 2-2)과 승인 거부 실패 경로(5-1 취소·반품) 통합 테스트(실 MariaDB·MockMvc·실 MockPaymentGateway).
 *
 * <p><b>트랜잭션</b>: 환불·알림·재고 복구가 승인 커밋 뒤 AFTER_COMMIT 체인이라 클래스·메서드에 {@code @Transactional}을 두지 않는다 —
 * 각 요청이 독립 트랜잭션으로 커밋되고, 부수효과 발생·미발생을 {@link JdbcTemplate}로 직접 조회한다.
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally) — Track80CancelFlowIntegrationTest 패턴.
 *
 * <p><b>경합</b>: 두 스레드를 ready/start 래치로 동시에 출발시킨다(ClaimLockRaceIntegrationTest 패턴). 승인은 주문 행
 * {@code SELECT ... FOR UPDATE}로 직렬화되고, 늦은 쪽은 잠금 뒤 상태 전이 검증에서 422로 끝나야 한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false",
        "zslab.order.auto-confirm.enabled=false"
})
class AdminClaimApproveRaceAndRefusalIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9881L;
    private static final long USER_ID = 9882L;
    private static final long SELLER_ID = 9881L;
    private static final long PRODUCT_ID = 9881L;
    private static final long VARIANT_ID = 9881L;
    private static final long INVENTORY_ID = 9881L;
    private static final long ORDER_ID = 9881L;
    private static final long ORDER_ITEM_ID = 9881L;
    private static final long PAYMENT_ID = 9881L;
    private static final long CLAIM_ID = 9881L;
    private static final long DUMMY_FK_ID = 9881L;
    private static final long ITEM_PRICE = 10_000L;
    private static final int INITIAL_ON_HAND = 10;
    private static final int RACE_THREADS = 2;
    private static final long RACE_TIMEOUT_SECONDS = 30L;
    private static final int HTTP_OK = 200;
    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;

    private static final String CLAIM_PID = pid("clm_", "A2BCLM");
    private static final String APPROVE_URL = "/api/v1/admin/claims/" + CLAIM_PID + "/approve";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
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

    // ===== 2-2: 같은 취소 클레임 동시 승인 =====

    @Test
    @DisplayName("R1 동시 승인: 같은 취소 클레임에 2스레드 → 200 1건·422 CLAIM_STATE_INVALID 1건 · 클레임 COMPLETED · 환불 1건 · 재고 복구 1회 · 승인 알림 1건")
    void concurrentApprove_cancelClaim_onlyOneTakesEffect() throws Exception {
        seedGraph("PAID", "CANCEL_REQUESTED", "CANCEL", "REQUESTED", "PAID");

        List<MockHttpServletResponse> responses = raceApprove();

        List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
        assertThat(statuses).containsExactly(HTTP_OK, HTTP_UNPROCESSABLE_ENTITY);
        MockHttpServletResponse loser = responses.stream()
                .filter(response -> response.getStatus() == HTTP_UNPROCESSABLE_ENTITY).findFirst().orElseThrow();
        assertThat(JsonPath.<String>read(loser.getContentAsString(), "$.code")).isEqualTo("CLAIM_STATE_INVALID");

        // 1회 승인 결과: Mock PG 자동 콜백까지 요청 스레드에서 수렴해 클레임 COMPLETED·품목 CANCELLED(Track80 T3와 같은 종착)
        assertThat(claimStatus()).isEqualTo("COMPLETED");
        assertThat(itemStatus()).isEqualTo("CANCELLED");
        assertThat(refundCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM refund WHERE claim_id = ?", String.class, CLAIM_ID)).isEqualTo("COMPLETED");
        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND + 1);
        assertThat(inventoryHistoryCount()).isEqualTo(1);
        assertThat(notificationCount(NotificationTemplateCodes.CLAIM_APPROVED)).isEqualTo(1);
        assertThat(approveAuditCount()).isEqualTo(1);
    }

    // ===== 5-1: 승인 거부(이미 APPROVED) =====

    @Test
    @DisplayName("F1 취소 승인 거부: 이미 APPROVED 취소 클레임 재승인 → 422 CLAIM_STATE_INVALID · 클레임·주문·품목·결제 불변 · 환불 0 · 재고 불변 · 알림 0 · 감사 0")
    void approveAlreadyApprovedCancel_refused_noSideEffects() throws Exception {
        seedGraph("PAID", "CANCEL_REQUESTED", "CANCEL", "APPROVED", "PAID");

        mockMvc.perform(post(APPROVE_URL).with(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        assertNothingChanged("PAID", "CANCEL_REQUESTED");
    }

    @Test
    @DisplayName("F2 반품 승인 거부: 이미 APPROVED 반품 클레임 재승인 → 422 CLAIM_STATE_INVALID · 클레임·주문·품목·결제 불변 · 환불 0 · 재고 불변 · 알림(SMS 포함) 0 · 감사 0")
    void approveAlreadyApprovedReturn_refused_noSideEffects() throws Exception {
        seedGraph("DELIVERED", "RETURN_REQUESTED", "RETURN", "APPROVED", "DELIVERED");

        mockMvc.perform(post(APPROVE_URL).with(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));

        assertNothingChanged("DELIVERED", "RETURN_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ? AND channel = 'SMS'",
                Integer.class, USER_ID)).isZero();
    }

    private void assertNothingChanged(String orderStatus, String itemStatus) {
        assertThat(claimStatus()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, ORDER_ID)).isEqualTo(orderStatus);
        assertThat(itemStatus()).isEqualTo(itemStatus);
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, PAYMENT_ID)).isEqualTo("PAID");
        assertThat(refundCount()).isZero();
        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
        assertThat(jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity_available FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID))
                .isEqualTo(INITIAL_ON_HAND);
        assertThat(inventoryHistoryCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ?", Integer.class, USER_ID))
                .isZero();
        assertThat(approveAuditCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM claim_suggestion_record WHERE claim_id = ?", Integer.class, CLAIM_ID))
                .isZero();
    }

    // ---------- 경합 ----------

    private List<MockHttpServletResponse> raceApprove() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACE_THREADS);
        CountDownLatch ready = new CountDownLatch(RACE_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MockHttpServletResponse> approve = () -> {
                ready.countDown();
                awaitQuietly(start);
                return mockMvc.perform(post(APPROVE_URL).with(authHeaders.admin(ADMIN_ID))).andReturn().getResponse();
            };
            Future<MockHttpServletResponse> first = pool.submit(approve);
            Future<MockHttpServletResponse> second = pool.submit(approve);
            awaitQuietly(ready);
            start.countDown();
            return List.of(first.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS), second.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
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

    // ---------- 조회 ----------

    // 모든 조회·시드 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private String claimStatus() {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, CLAIM_ID);
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, ORDER_ITEM_ID);
    }

    private int refundCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, CLAIM_ID);
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private int inventoryHistoryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ?", Integer.class, INVENTORY_ID);
    }

    private int notificationCount(String templateCode) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE target_type = 'CLAIM' AND target_id = ? "
                + "AND template_code = ?", Integer.class, CLAIM_ID, templateCode);
    }

    private int approveAuditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'CLAIM' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, CLAIM_ID);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    // ---------- 시드·정리 ----------

    /** buyer(phone)·admin·seller·product·variant·inventory(10/0/10)·order·order_item(1개·10,000원)·PAID payment·배송지·claim 1건. */
    private void seedGraph(String orderStatus, String itemStatus, String claimType, String claimStatus, String previousItemStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "A2BUSR"), "a2b@example.test", "A2B구매자", "010-1111-3333");
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?", USER_ID, "BUYER");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        ADMIN_ID, pid("usr_", "A2BADM"), "a2b-admin@example.test", "A2B관리자");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, 'A2B셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "A2BSLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'A2B상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "A2BPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCA2B', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "A2BVAR"), PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                                + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID, INITIAL_ON_HAND, INITIAL_ON_HAND);
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, 'ORDA2B', ?, ?, 0, 0, NOW(6), NOW(6), NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "A2BORD"), USER_ID, orderStatus, ITEM_PRICE);
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                                + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), 'A2B상품', 1000)",
                        ORDER_ITEM_ID, pid("oit_", "A2BOIT"), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus);
                jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, "
                                + "paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_a2b', 'pat_a2b', "
                                + "NOW(6), NOW(6), NOW(6))",
                        PAYMENT_ID, pid("pay_", "A2BPAY"), ORDER_ID, ITEM_PRICE);
                jdbc.update("INSERT INTO order_shipping_snapshot (order_id, recipient_name, recipient_phone, zonecode, address_road, "
                                + "address_detail, created_at, updated_at) VALUES (?, '홍길동', '010-1234-5678', '06236', '서울 강남대로 1', "
                                + "'101호', NOW(6), NOW(6))",
                        ORDER_ID);
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                                + "requested_by, requested_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'BUYER_CHANGED_MIND', ?, ?, ?, NOW(6), NOW(6), NOW(6))",
                        CLAIM_ID, CLAIM_PID, ORDER_ITEM_ID, claimType, claimStatus, previousItemStatus, USER_ID);
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
                jdbc.update("DELETE FROM audit_log WHERE target_type IN ('CLAIM', 'ORDER') AND target_id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM claim_suggestion_record WHERE claim_id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM refund WHERE claim_id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM payment WHERE order_id = ?", ORDER_ID);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id = ?", ORDER_ID);
                jdbc.update("DELETE FROM order_item WHERE id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", USER_ID, ADMIN_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

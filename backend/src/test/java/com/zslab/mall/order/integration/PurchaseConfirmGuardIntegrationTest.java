package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.exception.PurchaseConfirmBlockedException;
import com.zslab.mall.order.exception.PurchaseConfirmBlockedReason;
import com.zslab.mall.order.scheduler.OrderAutoConfirmScheduler;
import com.zslab.mall.order.service.OrderAutoConfirmService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매확정 가드 통합 테스트(Track 104-4·P2·P6·실 MariaDB). 품목 순수령액(total_price − RefundedCondition 기환불액) 0 이하·주문 OPEN 불일치면
 * 수동(구매자 API)·자동(스케줄러 단건) 확정이 같은 코어 가드로 막히고, 자동 확정 후보 조회는 두 조건의 품목을 조회 단계에서 뺀다(D-175 방식).
 *
 * <p>시드: 품목마다 주문 1건·원 발송 8일 전 배송완료 — 정상 / 전액 환불(다른 클레임 완료 환불 10,000) / 주문 OPEN 불일치 / 부분 환불 3,000(잔액 7,000).
 * 설정·목 빈은 {@code OrderAutoConfirmIntegrationTest}와 같게 둬 스프링 컨텍스트를 재사용한다(스케줄러는 직접 생성). 클래스에
 * {@code @Transactional}을 두지 않는다(실제 커밋 관찰·LT-02 FK 복원).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-confirm.enabled=false",
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class PurchaseConfirmGuardIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 10471L;
    private static final long SELLER_ID = 10471L;
    private static final long PRODUCT_ID = 10471L;
    private static final long VARIANT_ID = 10471L;
    private static final long DUMMY_FK_ID = 10471L;
    private static final long PAYMENT_ID = 10471L;
    private static final long ITEM_PRICE = 10_000L;
    private static final long PARTIAL_REFUND = 3_000L;
    private static final int DELIVERED_DAYS_AGO = 8;
    /** 시드 품목 4건: 품목·배송·클레임·환불 id = 품목 번호, 주문 id = 품목 번호 + 오프셋(주문 id·품목 id 혼동을 잡도록 다르게 둔다). */
    private static final long ORDER_ID_OFFSET = 100L;
    private static final long ITEM_NORMAL = 10471L;
    private static final long ITEM_NET_ZERO = 10472L;
    private static final long ITEM_OPEN_ISSUE = 10473L;
    private static final long ITEM_PARTIAL_REFUND = 10474L;
    private static final List<Long> ITEM_IDS = List.of(ITEM_NORMAL, ITEM_NET_ZERO, ITEM_OPEN_ISSUE, ITEM_PARTIAL_REFUND);
    private static final long ISSUE_ID = 10471L;

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
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        doNothing().when(smsSender).send(any(), any());
        cleanup();
        inFkOff(() -> {
            seedCatalog();
            for (long itemId : ITEM_IDS) {
                seedDeliveredItem(itemId);
            }
            seedCompletedRefund(ITEM_NET_ZERO, ITEM_PRICE);
            seedCompletedRefund(ITEM_PARTIAL_REFUND, PARTIAL_REFUND);
            jdbc.update("INSERT INTO reconciliation_issue (id, issue_type, dedupe_key, order_id, status, detected_at, created_at) "
                    + "VALUES (?, 'PAYMENT_CANCELLED_WITHOUT_REFUND', ?, ?, 'OPEN', NOW(6), NOW(6))",
                    ISSUE_ID, "pcg:" + ITEM_OPEN_ISSUE, orderIdOf(ITEM_OPEN_ISSUE));
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("G1 수동: 순수령액 0(품목 10,000 − 다른 클레임 완료 환불 10,000) → 422 PURCHASE_CONFIRM_NET_AMOUNT_NOT_POSITIVE·품목 DELIVERED·confirmed_at 없음")
    void manualConfirm_netAmountZero_blocked() throws Exception {
        confirm(ITEM_NET_ZERO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_CONFIRM_NET_AMOUNT_NOT_POSITIVE"));

        assertThat(itemStatus(ITEM_NET_ZERO)).isEqualTo("DELIVERED");
        assertThat(confirmedAt(ITEM_NET_ZERO)).isNull();
    }

    @Test
    @DisplayName("G2 수동: 주문 OPEN 불일치 → 422 PURCHASE_CONFIRM_RECONCILIATION_OPEN·DELIVERED → 불일치 해결(RESOLVED) 후 → 200·CONFIRMED")
    void manualConfirm_openIssue_blockedUntilResolved() throws Exception {
        confirm(ITEM_OPEN_ISSUE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_CONFIRM_RECONCILIATION_OPEN"));
        assertThat(itemStatus(ITEM_OPEN_ISSUE)).isEqualTo("DELIVERED");

        inFkOff(() -> jdbc.update("UPDATE reconciliation_issue SET status = 'RESOLVED', resolved_at = NOW(6), "
                + "resolution_memo = '확인 완료' WHERE id = ?", ISSUE_ID));

        confirm(ITEM_OPEN_ISSUE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(itemStatus(ITEM_OPEN_ISSUE)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("G3 수동 대조군: 부분 환불 3,000(잔액 7,000 > 0) → 200·CONFIRMED")
    void manualConfirm_partialRefund_allowed() throws Exception {
        confirm(ITEM_PARTIAL_REFUND)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(itemStatus(ITEM_PARTIAL_REFUND)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("G4 자동 단건(공통 코어 가드): 순수령액 0·OPEN 불일치 품목을 confirmOne으로 직접 확정 → 같은 사유 예외·품목 DELIVERED")
    void autoConfirmOne_sameGuardAsManual() {
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> orderAutoConfirmService.confirmOne(ITEM_NET_ZERO, now))
                .isInstanceOfSatisfying(PurchaseConfirmBlockedException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(PurchaseConfirmBlockedReason.NET_AMOUNT_NOT_POSITIVE));
        assertThatThrownBy(() -> orderAutoConfirmService.confirmOne(ITEM_OPEN_ISSUE, now))
                .isInstanceOfSatisfying(PurchaseConfirmBlockedException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(PurchaseConfirmBlockedReason.RECONCILIATION_OPEN));

        assertThat(itemStatus(ITEM_NET_ZERO)).isEqualTo("DELIVERED");
        assertThat(itemStatus(ITEM_OPEN_ISSUE)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("G5 자동 배치: 후보 조회에서 순수령액 0·OPEN 불일치 품목 제외 → 배치 실행 시 정상·부분 환불 품목만 CONFIRMED·차단 품목 DELIVERED")
    void autoConfirmBatch_excludesBlockedItemsAtQuery() {
        List<Long> candidates = deliveryRepository.findAutoConfirmCandidateOrderItemIds(
                        DeliveryDirection.OUTBOUND, DeliveryStatus.DELIVERED, LocalDateTime.now().minusDays(7),
                        OrderItemStatus.DELIVERED, PageRequest.of(0, 100))
                .stream().filter(ITEM_IDS::contains).toList();
        assertThat(candidates).containsExactly(ITEM_NORMAL, ITEM_PARTIAL_REFUND);

        new OrderAutoConfirmScheduler(deliveryRepository, orderAutoConfirmService).confirmBatch();

        assertThat(itemStatus(ITEM_NORMAL)).isEqualTo("CONFIRMED");
        assertThat(itemStatus(ITEM_PARTIAL_REFUND)).isEqualTo("CONFIRMED");
        assertThat(itemStatus(ITEM_NET_ZERO)).isEqualTo("DELIVERED");
        assertThat(itemStatus(ITEM_OPEN_ISSUE)).isEqualTo("DELIVERED");
    }

    // ---------- 요청·조회 ----------
    // 모든 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private ResultActions confirm(long itemId) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + orderPid(itemId) + "/items/" + itemPid(itemId) + "/confirm")
                .headers(authHeaders.buyer(USER_ID)));
    }

    private String itemStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, orderItemId);
    }

    private LocalDateTime confirmedAt(long orderItemId) {
        return jdbc.queryForObject("SELECT confirmed_at FROM order_item WHERE id = ?", LocalDateTime.class, orderItemId);
    }

    // ---------- 시드 ----------

    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "PCGUSR"), "pcg@example.test", "확정가드구매자");
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, '확정가드셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID, pid("slr_", "PCGSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, '확정가드상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "PCGPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, is_soldout_manual, "
                + "display_order, option1_value_id, created_at, updated_at) VALUES (?, ?, ?, 'VCPCG', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "PCGVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    /** 주문 1건 + DELIVERED 품목 1건 + 원 발송(OUTBOUND·claim_id NULL) 배송완료 8일 전(자동 확정 기한 경과). */
    private void seedDeliveredItem(long id) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'DELIVERED', ?, 0, 0, NOW(6), NOW(6))",
                orderIdOf(id), orderPid(id), USER_ID, "ORDPCG" + id, ITEM_PRICE);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, created_at, updated_at, product_name, commission_rate) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'DELIVERED', NOW(6), NOW(6), '확정가드상품', 1000)",
                id, itemPid(id), orderIdOf(id), PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE);
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, 'DELIVERED', "
                + "NOW(6) - INTERVAL ? DAY - INTERVAL 1 DAY, NOW(6) - INTERVAL ? DAY, NOW(6), NOW(6))",
                id, pid("dlv_", "PCGDLV" + id), id, "PCG-TRACK-" + id, DELIVERED_DAYS_AGO, DELIVERED_DAYS_AGO);
    }

    /** 품목에 거부된 반품 클레임 + 그 클레임의 완료 환불(RefundedCondition 합산 대상·활성 클레임 아님). 클레임·환불 id = 품목 id. */
    private void seedCompletedRefund(long itemId, long amount) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, requested_by, "
                + "requested_at, processed_at, created_at, updated_at) VALUES (?, ?, ?, 'RETURN', 'PRODUCT_DEFECT', 'REJECTED', 'DELIVERED', ?, "
                + "NOW(6), NOW(6), NOW(6), NOW(6))", itemId, pid("clm_", "PCGCLM" + itemId), itemId, USER_ID);
        jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, refunded_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, NOW(6), NOW(6), NOW(6))",
                itemId, pid("rfn_", "PCGRFN" + itemId), itemId, PAYMENT_ID, amount, "pcg_rfn_" + itemId);
    }

    private void cleanup() {
        inFkOff(() -> {
            jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
            jdbc.update("DELETE FROM reconciliation_issue WHERE id = ?", ISSUE_ID);
            jdbc.update("DELETE FROM refund WHERE id BETWEEN ? AND ?", ITEM_NORMAL, ITEM_PARTIAL_REFUND);
            jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ITEM_NORMAL, ITEM_PARTIAL_REFUND);
            jdbc.update("DELETE FROM delivery WHERE id BETWEEN ? AND ?", ITEM_NORMAL, ITEM_PARTIAL_REFUND);
            jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ITEM_NORMAL, ITEM_PARTIAL_REFUND);
            jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", orderIdOf(ITEM_NORMAL), orderIdOf(ITEM_PARTIAL_REFUND));
            jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
            jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
            jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
            jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
        });
    }

    private void inFkOff(Runnable work) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                work.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static long orderIdOf(long itemId) {
        return itemId + ORDER_ID_OFFSET;
    }

    private static String orderPid(long id) {
        return pid("ord_", "PCGORD" + id);
    }

    private static String itemPid(long id) {
        return pid("oit_", "PCGOIT" + id);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

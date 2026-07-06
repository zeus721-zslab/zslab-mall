package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * RETURN 클레임 전체 루프 E2E 통합 테스트(Track 14 PR-1·D-98 Q1·Q2·Q4·Q5·실 MariaDB). 요청 → 승인 → 수거 확인 →
 * 자동 환불(ClaimPickedUpHandler) → 환불 webhook → ClaimRefundCompleted → ClaimCompleted까지 실제 커밋·AFTER_COMMIT
 * 핸들러 체인으로 구동해 OrderItem DELIVERED → RETURN_REQUESTED → RETURNED 종결을 실측한다(라이브 트랩 차단).
 *
 * <p><b>트랜잭션</b>: AFTER_COMMIT 핸들러는 실제 커밋 후 실행되므로 클래스에 {@code @Transactional}을 두지 않는다
 * (ClaimEventIntegrationTest·RefundWebhookIntegrationTest 패턴 1:1). 시드/정리는 {@link TransactionTemplate} +
 * {@code FOREIGN_KEY_CHECKS=0}, 검증은 {@link JdbcTemplate} 직접 조회로 한다.
 */
@AutoConfigureMockMvc
class ClaimReturnIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9302L;
    private static final long SELLER_ID = 9302L;
    private static final long PRODUCT_ID = 9302L;
    private static final long VARIANT_ID = 9302L;
    private static final long ORDER_ID = 9302L;
    private static final long ORDER_ITEM_ID = 9302L;
    private static final long PAYMENT_ID = 9302L;
    private static final long DUMMY_FK_ID = 9302L;
    private static final long ITEM_PRICE = 10_000L;

    private static final String ORDER_ITEM_PID = pid("oit_", "RTNOIT");

    @Autowired
    private MockMvc mockMvc;
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
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("RETURN 전체 루프 e2e: 요청→승인→수거→자동환불→webhook → OrderItem RETURNED·Claim COMPLETED")
    void returnFullLoop_terminatesToReturned() throws Exception {
        seed(() -> {
            seedCatalog();
            seedOrder("DELIVERED");
            seedOrderItem(OrderItemStatus.DELIVERED);
            seedPayment();
        });

        // 1) 요청: DELIVERED → RETURN_REQUESTED(ClaimRequestedHandler·AFTER_COMMIT)
        Claim claim = claimService.request(new ClaimRequestCommand(
                ORDER_ITEM_PID, ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT, "하자", USER_ID, LocalDateTime.now()));
        Long claimId = claim.getId();
        assertThat(orderItemStatus()).isEqualTo("RETURN_REQUESTED");

        // 2) 승인: RETURN은 ClaimApprovedHandler 자동 환불 미대상(CANCEL만)·차액 없음(refundAmount=null)
        claimService.approve(claimId, LocalDateTime.now(), null);

        // 3) 수거 확인: ClaimPickedUp → ClaimPickedUpHandler → RefundService.initiate(PENDING 생성)
        claimService.confirmPickup(claimId, LocalDateTime.now());
        String pgRefundId = jdbc.queryForObject(
                "SELECT pg_refund_id FROM refund WHERE claim_id = ?", String.class, claimId);
        assertThat(pgRefundId).isNotNull();

        // 4) 환불 webhook SUCCESS → RefundCompleted → ClaimRefundCompletedHandler(RETURN→markCompleted)
        //    → ClaimCompleted → ClaimCompletedHandler(RETURN_REQUESTED→RETURNED)
        String body = "{ \"pgRefundId\": \"" + pgRefundId + "\", \"status\": \"SUCCESS\" }";
        mockMvc.perform(post("/api/webhooks/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(refundStatus(pgRefundId)).isEqualTo("COMPLETED");
        assertThat(claimStatus(claimId)).isEqualTo("COMPLETED");
        assertThat(orderItemStatus()).isEqualTo("RETURNED");
    }

    // ---------- seed·helpers (ClaimEventIntegrationTest·RefundWebhookIntegrationTest 패턴 1:1) ----------

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
                USER_ID, pid("usr_", "RTNUSR"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '통합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                SELLER_ID, pid("slr_", "RTNSLR"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '통합상품', 'SALE', 10000, NOW(6), NOW(6))",
                PRODUCT_ID, pid("prd_", "RTNPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCRTN', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                VARIANT_ID, pid("var_", "RTNVAR"), PRODUCT_ID, DUMMY_FK_ID);
    }

    private void seedOrder(String status) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6))",
                ORDER_ID, pid("ord_", "RTNORD"), USER_ID, "ORDRTN" + ORDER_ID, status, ITEM_PRICE);
    }

    private void seedOrderItem(OrderItemStatus itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6))",
                ORDER_ITEM_ID, ORDER_ITEM_PID, ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID,
                ITEM_PRICE, ITEM_PRICE, itemStatus.name());
    }

    private void seedPayment() {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, "
                        + "payment_attempt_key, paid_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_rtn_0001', 'pat_rtn_0001', NOW(6), NOW(6), NOW(6))",
                PAYMENT_ID, pid("pay_", "RTNPAY"), ORDER_ID, ITEM_PRICE);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id = ?)",
                        ORDER_ITEM_ID);
                jdbc.update("DELETE FROM claim WHERE order_item_id = ?", ORDER_ITEM_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
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

    private String refundStatus(String pgRefundId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE pg_refund_id = ?", String.class, pgRefundId);
    }

    private String claimStatus(Long claimId) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

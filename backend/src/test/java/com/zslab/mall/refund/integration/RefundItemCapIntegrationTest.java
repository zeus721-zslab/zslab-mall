package com.zslab.mall.refund.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.gateway.PgRefundResponse;
import com.zslab.mall.refund.service.RefundRecoveryService;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 품목 단위 환불 상한·이중 환불 차단 통합 테스트(Track 104-3a·invariants P1·실 MariaDB). 관리자 수동 환불이 품목 금액을 넘으면 결제 잔액이
 * 남아도 422인지, 실패 처리한 환불에 PG 성공이 온 뒤 재개시가 PG를 다시 부르지 않는지, 품목 금액만큼 이미 환불된 클레임을 복구 배치가
 * 건너뛰는지 본다. 관리자 목록의 재개시 액션(Java)과 "필요 액션" 필터(Specification)가 같은 판정인지도 함께 본다.
 *
 * <p>시드: 주문 1(결제 PAID 20,000) · 품목 A·B(각 10,000) · 품목 A 취소 클레임 APPROVED. {@link PaymentGateway}는 MockitoBean
 * (호출 횟수 = PG 환불 요청 횟수). 클래스에 {@code @Transactional}을 두지 않는다(실제 커밋 관찰·LT-02 FK 복원).
 */
@AutoConfigureMockMvc
class RefundItemCapIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN = 7201L;
    private static final long USER_ID = 10431L;
    private static final long SELLER_ID = 10431L;
    private static final long PRODUCT_ID = 10431L;
    private static final long VARIANT_ID = 10431L;
    private static final long DUMMY_FK_ID = 10431L;
    private static final long ORDER_ID = 10431L;
    private static final long ITEM_A_ID = 10431L;
    private static final long ITEM_B_ID = 10432L;
    private static final long PAYMENT_ID = 10431L;
    private static final long CLAIM_A_ID = 10431L;
    private static final long CLAIM_B_REJECTED_ID = 10432L;
    private static final long CLAIM_B_APPROVED_ID = 10433L;
    private static final long REFUND_B_ID = 10432L;
    private static final long ITEM_PRICE = 10_000L;
    private static final long PAYMENT_AMOUNT = 20_000L;
    private static final String ORDER_NO = "ORDRCAP-10431";
    private static final String CLAIM_A_PID = pid("clm_", "RCAPA");
    private static final String CLAIM_B_APPROVED_PID = pid("clm_", "RCAPB2");
    private static final String PG_REFUND_ID = "rcap_rfn_0001";
    private static final String CLAIMS_URL = "/api/v1/admin/claims";

    @MockitoBean
    private PaymentGateway paymentGateway;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private RefundRecoveryService refundRecoveryService;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedGraph();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("C1 관리자 수동 환불 15,000 > 품목 10,000 → 결제 잔액(20,000)이 남아도 422 REFUND_INVARIANT_VIOLATION·환불 0행·PG 미호출")
    void adminRefund_overItemPrice_blockedEvenWithPaymentBalance() throws Exception {
        initiateRefund(CLAIM_A_PID, 15_000L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_INVARIANT_VIOLATION"));

        assertThat(refundCount(CLAIM_A_ID)).isZero();
        verify(paymentGateway, never()).refund(any(), any());
    }

    @Test
    @DisplayName("C2 개시(PENDING) → FAIL(FAILED·재개시 노출) → SUCCESS(불일치·PG 성공 기록·재개시 숨김·배지) → 재개시 → 기존 행 반환·PG 재호출 없음")
    void pgSuccessOnFailedRefund_thenReinitiate_doesNotCallPgAgain() throws Exception {
        when(paymentGateway.refund(any(), any())).thenReturn(new PgRefundResponse(PG_REFUND_ID, true, null));
        initiateRefund(CLAIM_A_PID, ITEM_PRICE).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));

        refundWebhook("FAIL").andExpect(status().isOk());
        assertThat(refundStatus(CLAIM_A_ID)).isEqualTo("FAILED");
        JsonNode failedRow = claimRow(CLAIM_A_PID);
        assertThat(failedRow.get("availableActions").toString()).isEqualTo("[\"INITIATE_REFUND\"]");
        assertThat(failedRow.get("pgRefundSucceeded").asBoolean()).isFalse();
        assertThat(initiateRefundFilterCount()).isEqualTo(1);

        refundWebhook("SUCCESS").andExpect(status().isOk());
        assertThat(refundStatus(CLAIM_A_ID)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT pg_refund_succeeded_at IS NOT NULL FROM refund WHERE claim_id = ?", Boolean.class, CLAIM_A_ID))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_issue WHERE issue_type = 'PG_REFUND_SUCCESS_ON_FAILED' "
                + "AND order_id = ?", Integer.class, ORDER_ID)).isEqualTo(1);
        JsonNode succeededRow = claimRow(CLAIM_A_PID);
        assertThat(succeededRow.get("availableActions").size()).isZero();
        assertThat(succeededRow.get("pgRefundSucceeded").asBoolean()).isTrue();
        assertThat(initiateRefundFilterCount()).isZero();

        initiateRefund(CLAIM_A_PID, ITEM_PRICE).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));

        assertThat(refundCount(CLAIM_A_ID)).isEqualTo(1);
        verify(paymentGateway, times(1)).refund(any(), any());
    }

    @Test
    @DisplayName("C3 다른 클레임 환불로 품목 금액만큼 이미 나간 APPROVED 취소 클레임 → 복구 배치 제외(false)·환불 0행·PG 미호출·재개시 액션·필터 모두 제외")
    void recovery_itemFullyRefunded_excludedWithoutPgCall() throws Exception {
        seedItemBRefundedByRejectedClaim();

        assertThat(refundRecoveryService.recoverMissingRefund(CLAIM_B_APPROVED_ID)).isFalse();

        assertThat(refundCount(CLAIM_B_APPROVED_ID)).isZero();
        verify(paymentGateway, never()).refund(any(), any());
        assertThat(claimRow(CLAIM_B_APPROVED_PID).get("availableActions").size()).isZero();
        assertThat(initiateRefundFilterCount()).isEqualTo(1); // 품목 A 클레임만(환불 없음·잔여 있음)
    }

    // ---------- 요청·조회 ----------
    // 모든 SQL은 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private ResultActions initiateRefund(String claimPid, long amount) throws Exception {
        return mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/initiate-refund")
                .headers(authHeaders.admin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + "}"));
    }

    private ResultActions refundWebhook(String status) throws Exception {
        return mockMvc.perform(post("/api/webhooks/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pgRefundId\": \"" + PG_REFUND_ID + "\", \"status\": \"" + status + "\"}"));
    }

    /** 주문번호 검색으로 이 시드의 클레임 행만 조회해 claimId가 일치하는 행을 돌려준다(목록 Java 판정). */
    private JsonNode claimRow(String claimPid) throws Exception {
        for (JsonNode item : listClaims(null).get("items")) {
            if (claimPid.equals(item.get("claimId").asText())) {
                return item;
            }
        }
        throw new AssertionError("클레임 행 없음: " + claimPid);
    }

    /** "필요 액션 = 환불 개시" 필터 결과 건수(Specification 판정). */
    private long initiateRefundFilterCount() throws Exception {
        return listClaims("INITIATE_REFUND").get("totalCount").asLong();
    }

    private JsonNode listClaims(String action) throws Exception {
        var request = get(CLAIMS_URL).headers(authHeaders.admin(ADMIN)).param("keyword", ORDER_NO);
        if (action != null) {
            request = request.param("action", action);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private int refundCount(long claimId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, claimId);
    }

    private String refundStatus(long claimId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE claim_id = ?", String.class, claimId);
    }

    // ---------- 시드 ----------

    private void seedGraph() {
        inFkOff(() -> {
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                    USER_ID, pid("usr_", "RCAPUSR"));
            jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                    + "VALUES (?, ?, '상한셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID, pid("slr_", "RCAPSLR"));
            jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, '상한상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "RCAPPRD"), SELLER_ID, DUMMY_FK_ID);
            jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, is_soldout_manual, "
                    + "display_order, option1_value_id, created_at, updated_at) VALUES (?, ?, ?, 'VCRCAP', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                    VARIANT_ID, pid("var_", "RCAPVAR"), PRODUCT_ID, DUMMY_FK_ID);
            jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                    ORDER_ID, pid("ord_", "RCAPORD"), USER_ID, ORDER_NO, PAYMENT_AMOUNT);
            seedItem(ITEM_A_ID, "RCAPOITA", "CANCEL_REQUESTED");
            seedItem(ITEM_B_ID, "RCAPOITB", "CANCEL_REQUESTED");
            jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, "
                    + "paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_rcap_0001', 'pat_rcap_0001', "
                    + "NOW(6), NOW(6), NOW(6))", PAYMENT_ID, pid("pay_", "RCAPPAY"), ORDER_ID, PAYMENT_AMOUNT);
            seedClaim(CLAIM_A_ID, CLAIM_A_PID, ITEM_A_ID, "APPROVED");
        });
    }

    /** 품목 B: 거부된 취소 클레임에 완료 환불 10,000(품목 금액 전부) + 환불 없는 APPROVED 취소 클레임(복구 배치 후보 조건). */
    private void seedItemBRefundedByRejectedClaim() {
        inFkOff(() -> {
            seedClaim(CLAIM_B_REJECTED_ID, pid("clm_", "RCAPB1"), ITEM_B_ID, "REJECTED");
            jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, refunded_at, created_at, "
                    + "updated_at) VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'rcap_rfn_b001', NOW(6), NOW(6), NOW(6))",
                    REFUND_B_ID, pid("rfn_", "RCAPRFNB"), CLAIM_B_REJECTED_ID, PAYMENT_ID, ITEM_PRICE);
            seedClaim(CLAIM_B_APPROVED_ID, CLAIM_B_APPROVED_PID, ITEM_B_ID, "APPROVED");
        });
    }

    private void seedItem(long id, String tag, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), "
                + "'상한상품', 1000)", id, pid("oit_", tag), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus);
    }

    private void seedClaim(long id, String publicId, long itemId, String claimStatus) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, requested_by, "
                + "requested_at, processed_at, created_at, updated_at) VALUES (?, ?, ?, 'CANCEL', 'CHANGE_MIND', ?, 'PAID', ?, "
                + "NOW(6), TIMESTAMPADD(HOUR, -1, NOW(6)), NOW(6), NOW(6))", id, publicId, itemId, claimStatus, USER_ID);
    }

    private void cleanup() {
        inFkOff(() -> {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'REFUND' AND target_id IN (SELECT id FROM refund WHERE payment_id = ?)",
                    PAYMENT_ID);
            jdbc.update("DELETE FROM reconciliation_issue WHERE order_id = ?", ORDER_ID);
            jdbc.update("DELETE FROM refund WHERE payment_id = ?", PAYMENT_ID);
            jdbc.update("DELETE FROM claim WHERE id IN (?, ?, ?)", CLAIM_A_ID, CLAIM_B_REJECTED_ID, CLAIM_B_APPROVED_ID);
            jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
            jdbc.update("DELETE FROM order_item WHERE order_id = ?", ORDER_ID);
            jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
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

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

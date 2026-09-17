package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.order.service.OrderShippingService;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Track 80 배송 전 취소 흐름 E2E 통합 테스트(D-169·실 MariaDB·Flyway V23·MockMvc·실 MockPaymentGateway). 거부 사유·발송 가드·환불 자동 완료·
 * SMS 알림·관리자 목록을 실 커밋 경로로 검증한다.
 *
 * <p><b>트랜잭션</b>: 클레임 파이프라인(요청 접수 SMS·환불 자동 콜백·완료·재고 복구)이 AFTER_COMMIT 체인이라 클래스에 {@code @Transactional}을
 * 두지 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), 검증은 {@link JdbcTemplate}.
 * ({@code AdminOrderIntegrationTest} 패턴 1:1)
 *
 * <p><b>SMS</b>: {@link SmsSender}를 {@link MockitoBean}으로 대체해 수신번호·본문 호출을 검증하고 발송 실패(T5)를 주입한다. 번호 마스킹은
 * {@code PhoneMaskerTest}·{@code MockSmsSenderTest}가 단위로 검증한다.
 *
 * <p><b>시드 그래프</b>: buyer(user·phone 보유)·seller·product·variant 2·inventory 2 / 주문 A(PAID·품목 2·PAID payment) / 주문 B(PAID·품목 1).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-cancel.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class Track80CancelFlowIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9800L;
    private static final long USER_ID = 9801L;
    private static final long SELLER_ID = 9801L;
    private static final long PRODUCT_ID = 9801L;
    private static final long VARIANT_1 = 9801L;
    private static final long VARIANT_2 = 9802L;
    private static final long INVENTORY_1 = 9801L;
    private static final long INVENTORY_2 = 9802L;
    private static final long ORDER_A = 9801L;
    private static final long ORDER_A_ITEM_1 = 9801L;
    private static final long ORDER_A_ITEM_2 = 9802L;
    private static final long ORDER_B = 9802L;
    private static final long ORDER_B_ITEM = 9803L;
    private static final long PAYMENT_A = 9801L;
    private static final long PAYMENT_B = 9802L;
    private static final long DUMMY_FK_ID = 9801L;
    private static final long ITEM_PRICE = 10_000L;
    private static final String BUYER_PHONE = "010-1111-2222";
    private static final String BUYER_NAME = "트랙80구매자";
    /** 실측 8 고정: claim count·page + user·order_item·주문 요약 projection·refund·클레임 delivery(Track 81-A) 배치 5 + REQUESTED count 1. */
    private static final int QUERY_BUDGET_FOR_LIST = 8;

    private static final String ORDER_A_PID = pid("ord_", "T80ORDA");
    private static final String ORDER_B_PID = pid("ord_", "T80ORDB");
    private static final String ITEM_A1_PID = pid("oit_", "T80OITA1");
    private static final String ITEM_A2_PID = pid("oit_", "T80OITA2");
    private static final String ITEM_B_PID = pid("oit_", "T80OITB");
    private static final String ORDER_A_NO = "ORDT80A";
    private static final String ORDER_B_NO = "ORDT80B";
    private static final String CLAIMS_URL = "/api/v1/admin/claims";
    private static final String REJECT_BODY = "{\"reasonCode\":\"OUT_OF_POLICY\",\"memo\":\"정책 위반\"}";
    private static final String ALREADY_SHIPPED_BODY = "{\"reasonCode\":\"ALREADY_SHIPPED\"}";
    private static final String SHIPMENT_BODY = "{\"carrier\":\"CJ\",\"trackingNo\":\"T80TRACK0001\"}";

    @MockitoBean
    private SmsSender smsSender;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private OrderShippingService orderShippingService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        doNothing().when(smsSender).send(any(), any());
        cleanup();
        seedGraph();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ===== T1: 거부 사유(V23·C3) =====

    @Test
    @DisplayName("T1 거부: 사유 누락 400 → ALREADY_SHIPPED를 RETURN에 400 → 정상 거부 200·사유/메모 저장·품목 PAID 복원·거부 SMS")
    void reject_reasonRequired_typeGuard_restoresItem_sendsSms() throws Exception {
        String claimPid = requestCancel(ITEM_A1_PID);
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("CANCEL_REQUESTED");

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/reject").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"memo\":\"사유 없음\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/reject").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isBadRequest());
        assertThat(claimStatus(claimPid)).isEqualTo("REQUESTED");

        // RETURN 클레임(배송완료 품목 B·Track 81-A 요청 조건: DELIVERED+발송 배송완료 7일 이내)에 ALREADY_SHIPPED → 도메인 검증 400·상태 불변
        markDeliveredOutbound(ORDER_B_ITEM, 9891L, "T80DLVB1");
        String returnPid = requestClaim(ITEM_B_PID, ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT);
        mockMvc.perform(post(CLAIMS_URL + "/" + returnPid + "/reject").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(ALREADY_SHIPPED_BODY))
                .andExpect(status().isBadRequest());
        assertThat(claimStatus(returnPid)).isEqualTo("REQUESTED");

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/reject").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(REJECT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReasonCode").value("OUT_OF_POLICY"))
                .andExpect(jsonPath("$.rejectMemo").value("정책 위반"))
                .andExpect(jsonPath("$.refundStatus").doesNotExist());

        assertThat(claimStatus(claimPid)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT reject_reason_code FROM claim WHERE public_id = ?", String.class, claimPid))
                .isEqualTo("OUT_OF_POLICY");
        assertThat(jdbc.queryForObject("SELECT reject_memo FROM claim WHERE public_id = ?", String.class, claimPid))
                .isEqualTo("정책 위반");
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("PAID"); // 스냅샷 원복(ClaimRejectedHandler)
        assertThat(smsLogs("TPL_CLAIM_REJECTED")).hasSize(1);
        assertThat(smsLogs("TPL_CLAIM_REJECTED").get(0).get("status")).isEqualTo("SENT");
        verify(smsSender).send(eq(BUYER_PHONE), contains("취소 요청이 거부되었습니다. 사유: 정책상 불가"));
    }

    @Test
    @DisplayName("T1-b V23 호환: 거부 컬럼 없이 삽입된 기존 행은 NULL로 읽힌다(백필 없음)")
    void legacyClaimRow_rejectColumnsNullable() {
        long legacyClaimId = 9899L;
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, "
                        + "requested_by, requested_at, processed_at, previous_order_item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CANCEL', 'OTHER', 'REJECTED', ?, NOW(6), NOW(6), 'PAID', NOW(6), NOW(6))",
                legacyClaimId, pid("clm_", "T80LEGACY"), ORDER_A_ITEM_2, USER_ID));
        Map<String, Object> row = jdbc.queryForMap("SELECT reject_reason_code, reject_memo FROM claim WHERE id = ?", legacyClaimId);
        assertThat(row.get("reject_reason_code")).isNull();
        assertThat(row.get("reject_memo")).isNull();
    }

    // ===== T2: 발송 가드(C2) =====

    @Test
    @DisplayName("T2 발송 가드: 취소 요청 품목 송장 등록 422(관리자·셀러) → ALREADY_SHIPPED 거부 후 등록 200·SHIPPING")
    void prepareShipment_blockedWhileCancelRequested_thenRejectAndShip() throws Exception {
        String claimPid = requestCancel(ITEM_A1_PID);

        mockMvc.perform(post("/api/v1/admin/orders/items/" + ITEM_A1_PID + "/prepare-shipment")
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SHIPMENT_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));
        assertThatThrownBy(() -> orderShippingService.prepareShipment(SELLER_ID, ORDER_A_ITEM_1, DeliveryCarrier.CJ, "T80TRACK0002"))
                .isInstanceOf(ClaimInvalidStateException.class);
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("CANCEL_REQUESTED");
        assertThat(deliveryCount(ORDER_A_ITEM_1)).isZero();

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/reject").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(ALREADY_SHIPPED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectReasonCode").value("ALREADY_SHIPPED"));
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("PAID");

        mockMvc.perform(post("/api/v1/admin/orders/items/" + ITEM_A1_PID + "/prepare-shipment")
                        .headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SHIPMENT_BODY))
                .andExpect(status().isOk());
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("SHIPPING");
        assertThat(deliveryCount(ORDER_A_ITEM_1)).isEqualTo(1);
    }

    // ===== T3: 환불 자동 완료(C4) + SMS(C5) =====

    @Test
    @DisplayName("T3 승인 → Mock PG 자동 콜백 → Refund/Claim COMPLETED·품목 CANCELLED·재고 복구·요청/완료 SMS·사용자 응답 refundStatus")
    void approve_autoRefundCompletes_restoresStock_sendsSms() throws Exception {
        String claimPid = requestCancel(ITEM_A1_PID);
        assertThat(smsLogs("TPL_CLAIM_REQUESTED")).hasSize(1);
        verify(smsSender).send(eq(BUYER_PHONE), contains("주문 " + ORDER_A_NO + " 트랙80상품A 취소 요청이 접수되었습니다."));

        // 승인 응답 재조회 시점엔 AFTER_COMMIT 체인(자동 콜백 → 완료)이 요청 스레드에서 이미 수렴해 COMPLETED다
        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/approve").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(refundStatus(claimPid)).isEqualTo("COMPLETED");
        assertThat(claimStatus(claimPid)).isEqualTo("COMPLETED");
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("CANCELLED");
        assertThat(onHand(VARIANT_1)).isEqualTo(11);
        assertThat(smsLogs("TPL_CLAIM_COMPLETED")).hasSize(1);
        verify(smsSender).send(eq(BUYER_PHONE), contains("취소 및 환불이 완료되었습니다."));

        // 사용자 단건 응답에 환불 상태·거부 사유(null) 노출
        mockMvc.perform(get("/api/v1/claims/" + claimPid).headers(authHeaders.buyer(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.rejectReasonCode").doesNotExist());
        mockMvc.perform(get("/api/v1/claims").headers(authHeaders.buyer(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].refundStatus").value("COMPLETED"));
        // 관리자 주문 상세 클레임 행에도 동일 필드
        mockMvc.perform(get("/api/v1/admin/orders/" + ORDER_A_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].claims[0].refundStatus").value("COMPLETED"));
    }

    // ===== T4·T5: SMS skip·발송 실패 격리 =====

    @Test
    @DisplayName("T4 SMS skip: 구매자 phone NULL → SMS 로그 미적재·클레임 REQUESTED 정상")
    void requestSms_skippedWhenPhoneMissing() {
        jdbc.update("UPDATE `user` SET phone = NULL WHERE id = ?", USER_ID);

        String claimPid = requestCancel(ITEM_A1_PID);

        assertThat(claimStatus(claimPid)).isEqualTo("REQUESTED");
        assertThat(smsLogs("TPL_CLAIM_REQUESTED")).isEmpty();
    }

    @Test
    @DisplayName("T5 발송 실패 격리: SmsSender 예외 → NotificationLog FAILED·클레임 REJECTED 유지(롤백 없음)")
    void rejectSms_failureDoesNotRollbackClaim() throws Exception {
        String claimPid = requestCancel(ITEM_A1_PID);
        doThrow(new IllegalStateException("SMS 게이트웨이 장애")).when(smsSender).send(any(), any());

        mockMvc.perform(post(CLAIMS_URL + "/" + claimPid + "/reject").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(REJECT_BODY))
                .andExpect(status().isOk());

        assertThat(claimStatus(claimPid)).isEqualTo("REJECTED");
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("PAID");
        List<Map<String, Object>> logs = smsLogs("TPL_CLAIM_REJECTED");
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("status")).isEqualTo("FAILED");
    }

    // ===== T6: 관리자 목록(C7) =====

    @Test
    @DisplayName("T6 목록: 유형·상태·기간·검색(주문번호/구매자/상품명)·pendingCount·정렬·쿼리 예산·BUYER 403")
    void list_filtersPendingCountQueryBudgetAndAuth() throws Exception {
        String cancelPid = requestCancel(ITEM_A1_PID);
        markDeliveredOutbound(ORDER_B_ITEM, 9892L, "T80DLVB2");
        String returnPid = requestClaim(ITEM_B_PID, ClaimType.RETURN, ClaimReasonCode.PRODUCT_DEFECT);
        mockMvc.perform(post(CLAIMS_URL + "/" + returnPid + "/reject").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(REJECT_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.buyer(USER_ID)))
                .andExpect(status().isForbidden());

        // 전체: 2건·최신 우선(RETURN이 뒤에 요청됨)·pendingCount=1(CANCEL REQUESTED만)
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(returnPid))
                .andExpect(jsonPath("$.items[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.items[0].rejectReasonCode").value("OUT_OF_POLICY"))
                .andExpect(jsonPath("$.items[0].rejectMemo").value("정책 위반"))
                .andExpect(jsonPath("$.items[0].availableActions").isEmpty())
                .andExpect(jsonPath("$.items[1].claimId").value(cancelPid))
                .andExpect(jsonPath("$.items[1].orderNo").value(ORDER_A_NO))
                .andExpect(jsonPath("$.items[1].orderId").value(ORDER_A_PID))
                .andExpect(jsonPath("$.items[1].orderItemId").value(ITEM_A1_PID))
                .andExpect(jsonPath("$.items[1].buyerName").value(BUYER_NAME))
                .andExpect(jsonPath("$.items[1].productName").value("트랙80상품A"))
                .andExpect(jsonPath("$.items[1].quantity").value(1))
                .andExpect(jsonPath("$.items[1].amount").value(ITEM_PRICE))
                .andExpect(jsonPath("$.items[1].reasonCode").value("BUYER_CHANGED_MIND"))
                .andExpect(jsonPath("$.items[1].refundStatus").doesNotExist())
                .andExpect(jsonPath("$.items[1].availableActions[0]").value("APPROVE"))
                .andExpect(jsonPath("$.items[1].availableActions[1]").value("REJECT"));

        // 유형 탭: RETURN → 1건·pendingCount 0(RETURN REQUESTED 없음)
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("type", "RETURN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.pendingCount").value(0))
                .andExpect(jsonPath("$.items[0].claimId").value(returnPid));
        // 상태·정렬
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("status", "REQUESTED").param("sort", "OLDEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(cancelPid));
        // 검색: 주문번호 정확·구매자 이름·상품명 부분·무관 키워드 0건
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", ORDER_B_NO))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(returnPid));
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "트랙80구매"))
                .andExpect(jsonPath("$.totalCount").value(2));
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "상품B"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].claimId").value(returnPid));
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "없는키워드"))
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.pendingCount").value(1));
        // 기간: 미래 from → 0건 / from>to 400 / 잘못된 enum 400
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("from", "2999-01-01T00:00:00"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID))
                        .param("from", "2026-02-01T00:00:00").param("to", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("type", "REFUND"))
                .andExpect(status().isBadRequest());

        // 쿼리 수 고정(N+1 회피): 1행 페이지와 2행 페이지(주문·구매자·품목이 다른 클레임 2건)의 실행 쿼리 수가 같고 예산 이하
        long oneRowQueries = countListQueries(1);
        long twoRowQueries = countListQueries(2);
        assertThat(twoRowQueries).isEqualTo(oneRowQueries);
        assertThat(twoRowQueries).isEqualTo(QUERY_BUDGET_FOR_LIST);
    }

    /**
     * 목록 1회 호출의 SQL 실행 수(Hibernate Statistics·prepared statement 기준). 실측 구성(1행·2행 동일 8):
     * claim count · claim page · user in · order_item in · 품목→주문 요약 projection · claim REQUESTED count · refund in · delivery in(claim_id·Track 81-A).
     */
    private long countListQueries(int size) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(get(CLAIMS_URL).headers(authHeaders.admin(ADMIN_ID)).param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(size));
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return count;
    }

    // ===== helpers =====

    /** 품목을 DELIVERED로 두고 발송(OUTBOUND) 배송완료 Delivery(어제)를 시드한다 — RETURN 요청 조건(Track 81-A). */
    private void markDeliveredOutbound(long orderItemId, long deliveryId, String tag) {
        jdbc.update("UPDATE order_item SET item_status = 'DELIVERED' WHERE id = ?", orderItemId);
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, 'DELIVERED', NOW(6) - INTERVAL 2 DAY, "
                        + "NOW(6) - INTERVAL 1 DAY, NOW(6), NOW(6))",
                deliveryId, pid("dlv_", tag), orderItemId, "TRK" + tag);
    }

    private String requestCancel(String orderItemPid) {
        return requestClaim(orderItemPid, ClaimType.CANCEL, ClaimReasonCode.BUYER_CHANGED_MIND);
    }

    private String requestClaim(String orderItemPid, ClaimType type, ClaimReasonCode reasonCode) {
        return claimService.request(new ClaimRequestCommand(orderItemPid, type, reasonCode, "테스트", USER_ID,
                LocalDateTime.now())).getPublicId();
    }

    private String claimStatus(String claimPid) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE public_id = ?", String.class, claimPid);
    }

    private String refundStatus(String claimPid) {
        return jdbc.queryForObject("SELECT r.status FROM refund r JOIN claim c ON c.id = r.claim_id WHERE c.public_id = ?",
                String.class, claimPid);
    }

    private String itemStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, orderItemId);
    }

    private int deliveryCount(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE order_item_id = ?", Integer.class, orderItemId);
    }

    private int onHand(long variantId) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE variant_id = ?", Integer.class, variantId);
    }

    private List<Map<String, Object>> smsLogs(String templateCode) {
        return jdbc.queryForList("SELECT status, content FROM notification_log WHERE recipient_user_id = ? AND channel = 'SMS' "
                + "AND template_code = ?", USER_ID, templateCode);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T80USR"), "t80@example.test", BUYER_NAME, BUYER_PHONE);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙80셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "T80SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, '트랙80상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "T80PRD"), SELLER_ID, DUMMY_FK_ID);
                seedVariant(VARIANT_1, pid("var_", "T80VAR1"), "VCT80A", 1);
                seedVariant(VARIANT_2, pid("var_", "T80VAR2"), "VCT80B", 2);
                seedInventory(INVENTORY_1, VARIANT_1, 10, 0, 10);
                seedInventory(INVENTORY_2, VARIANT_2, 10, 0, 10);

                seedOrder(ORDER_A, ORDER_A_PID, ORDER_A_NO, 2 * ITEM_PRICE);
                seedOrderItem(ORDER_A_ITEM_1, ITEM_A1_PID, ORDER_A, VARIANT_1, "트랙80상품A");
                seedOrderItem(ORDER_A_ITEM_2, ITEM_A2_PID, ORDER_A, VARIANT_2, "트랙80상품A-2");
                seedPayment(PAYMENT_A, pid("pay_", "T80PAYA"), ORDER_A, 2 * ITEM_PRICE, "tid_track80_a", "pat_track80_a");
                seedSnapshot(ORDER_A);

                seedOrder(ORDER_B, ORDER_B_PID, ORDER_B_NO, ITEM_PRICE);
                seedOrderItem(ORDER_B_ITEM, ITEM_B_PID, ORDER_B, VARIANT_2, "트랙80상품B");
                seedPayment(PAYMENT_B, pid("pay_", "T80PAYB"), ORDER_B, ITEM_PRICE, "tid_track80_b", "pat_track80_b");
                seedSnapshot(ORDER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedVariant(long id, String publicId, String code, int displayOrder) {
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, 'SALE', 0, ?, ?, NOW(6), NOW(6))",
                id, publicId, PRODUCT_ID, code, displayOrder, DUMMY_FK_ID);
    }

    private void seedInventory(long id, long variantId, int onHand, int reserved, int available) {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, variantId, onHand, reserved, available);
    }

    private void seedOrder(long id, String publicId, String orderNo, long totalPrice) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6), NOW(6), NOW(6))",
                id, publicId, USER_ID, orderNo, totalPrice);
    }

    private void seedOrderItem(long id, String publicId, long orderId, long variantId, String productName) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, created_at, updated_at, product_name) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'PAID', NOW(6), NOW(6), ?)",
                id, publicId, orderId, PRODUCT_ID, variantId, SELLER_ID, ITEM_PRICE, ITEM_PRICE, productName);
    }

    private void seedPayment(long id, String publicId, long orderId, long amount, String pgTid, String attemptKey) {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, "
                        + "paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', ?, ?, NOW(6), NOW(6), NOW(6))",
                id, publicId, orderId, amount, pgTid, attemptKey);
    }

    private void seedSnapshot(long orderId) {
        jdbc.update("INSERT INTO order_shipping_snapshot (order_id, recipient_name, recipient_phone, zonecode, address_road, "
                        + "address_detail, created_at, updated_at) VALUES (?, '홍길동', '010-1234-5678', '06236', '서울 강남대로 1', "
                        + "'101호', NOW(6), NOW(6))",
                orderId);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'ORDER' AND target_id IN (?, ?)", ORDER_A, ORDER_B);
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id IN (?, ?, ?))",
                        ORDER_A_ITEM_1, ORDER_A_ITEM_2, ORDER_B_ITEM);
                jdbc.update("DELETE FROM claim WHERE order_item_id IN (?, ?, ?)", ORDER_A_ITEM_1, ORDER_A_ITEM_2, ORDER_B_ITEM);
                jdbc.update("DELETE FROM delivery WHERE order_item_id IN (?, ?, ?)", ORDER_A_ITEM_1, ORDER_A_ITEM_2, ORDER_B_ITEM);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id IN (?, ?)", INVENTORY_1, INVENTORY_2);
                jdbc.update("DELETE FROM inventory WHERE id IN (?, ?)", INVENTORY_1, INVENTORY_2);
                jdbc.update("DELETE FROM payment WHERE order_id IN (?, ?)", ORDER_A, ORDER_B);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (?, ?)", ORDER_A, ORDER_B);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (?, ?)", ORDER_A, ORDER_B);
                jdbc.update("DELETE FROM `order` WHERE id IN (?, ?)", ORDER_A, ORDER_B);
                jdbc.update("DELETE FROM product_variant WHERE id IN (?, ?)", VARIANT_1, VARIANT_2);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

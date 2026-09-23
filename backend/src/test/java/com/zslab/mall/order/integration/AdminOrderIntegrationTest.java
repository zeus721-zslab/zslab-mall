package com.zslab.mall.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.claim.controller.request.ClaimRequestCommand;
import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.order.service.AdminOrderCancelService;
import com.zslab.mall.support.AbstractIntegrationTest;
import com.zslab.mall.common.security.AuthHeaders;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 주문 관리 E2E 통합 테스트(Track 79 D-168·실 MariaDB·Flyway·MockMvc). 목록/상세/취소/송장 API와 사용자 취소 결함 보강
 * (동시 Claim 차단·정산 제외는 별도)을 실 커밋 경로로 검증한다.
 *
 * <p><b>트랜잭션</b>: Claim 파이프라인(환불 initiate·완료·재고 복구)이 AFTER_COMMIT 체인이라 클래스에 {@code @Transactional}을 두지
 * 않는다. 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), 검증은 {@link JdbcTemplate}.
 *
 * <p><b>시드 그래프</b>: buyer(user)·seller·product·variant 2·inventory 2 / 주문 A(PAID·품목 2·PAID payment) / 주문 B(PENDING_PAYMENT·
 * 품목 1·reserved) / 주문 C(PAID·품목 1 SHIPPING·delivery SHIPPING). 동적 id 행(claim·refund·delivery·audit·notification)은 buyer·
 * 주문 id 기준으로 정리한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-cancel.enabled=false",
        "zslab.refund.recovery.enabled=false",
        "zslab.order.expired-cleanup.enabled=false"
})
class AdminOrderIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9790L;
    private static final long USER_ID = 9791L;
    private static final long SELLER_ID = 9791L;
    private static final long PRODUCT_ID = 9791L;
    private static final long VARIANT_1 = 9791L;
    private static final long VARIANT_2 = 9792L;
    private static final long INVENTORY_1 = 9791L;
    private static final long INVENTORY_2 = 9792L;
    private static final long ORDER_A = 9791L;
    private static final long ORDER_A_ITEM_1 = 9791L;
    private static final long ORDER_A_ITEM_2 = 9792L;
    private static final long ORDER_B = 9792L;
    private static final long ORDER_B_ITEM = 9793L;
    private static final long ORDER_C = 9793L;
    private static final long ORDER_C_ITEM = 9794L;
    private static final long PAYMENT_A = 9791L;
    private static final long PAYMENT_B = 9792L;
    private static final long DELIVERY_C = 9791L;
    private static final long DUMMY_FK_ID = 9791L;
    private static final long ITEM_PRICE = 10_000L;
    private static final int THREADS = 8;
    private static final long WORKER_TIMEOUT_SECONDS = 30L;
    /** count·page·items fetch join + payment·delivery·claim·user·seller 배치 5 = 8 + 인증 필터 회원 상태 조회 1(Track 84) = 9. */
    private static final int QUERY_BUDGET_FOR_LIST = 9;

    private static final String ORDER_A_PID = pid("ord_", "T79ORDA");
    private static final String ORDER_B_PID = pid("ord_", "T79ORDB");
    private static final String ORDER_C_PID = pid("ord_", "T79ORDC");
    private static final String ITEM_A1_PID = pid("oit_", "T79OITA1");
    private static final String ITEM_A2_PID = pid("oit_", "T79OITA2");
    private static final String ITEM_B_PID = pid("oit_", "T79OITB");
    private static final String ITEM_C_PID = pid("oit_", "T79OITC");
    private static final String URL = "/api/v1/admin/orders";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private AdminOrderCancelService adminOrderCancelService;
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
        cleanup();
        seedGraph();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ===== D: 동시 Claim 차단 =====

    @Test
    @DisplayName("T1 동시 Claim 요청(D): 같은 품목 8스레드 사용자 취소 요청 → 1건 REQUESTED·7건 422(CLM-5/전이 불가)·승인 후 Refund 1건")
    void concurrentClaimRequests_onlyOneSucceeds() throws Exception {
        List<Throwable> failures = runConcurrently(() -> {
            claimService.request(new ClaimRequestCommand(ITEM_A1_PID, ClaimType.CANCEL, ClaimReasonCode.BUYER_CHANGED_MIND,
                    null, USER_ID, LocalDateTime.now()));
            return null;
        });

        assertThat(failures).hasSize(THREADS - 1)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(ClaimInvalidStateException.class));
        assertThat(claimCount(ORDER_A_ITEM_1)).isEqualTo(1);
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("CANCEL_REQUESTED");

        // 승인(관리자 단건 API·기존) → AFTER_COMMIT 환불 initiate → Refund 1건
        String claimPid = jdbc.queryForObject("SELECT public_id FROM claim WHERE order_item_id = ?", String.class, ORDER_A_ITEM_1);
        mockMvc.perform(post("/api/v1/admin/claims/" + claimPid + "/approve").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(refundCountForItem(ORDER_A_ITEM_1)).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 사용자·관리자 동시 취소: 같은 품목에 사용자 요청 4 + 관리자 취소 4 스레드 → Claim 1건만 생성")
    void concurrentBuyerAndAdminCancel_onlyOneClaim() throws Exception {
        AuditContext adminContext = AuditContext.of(ADMIN_ID, "ADMIN");
        List<Callable<Void>> works = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            if (i % 2 == 0) {
                works.add(() -> {
                    claimService.request(new ClaimRequestCommand(ITEM_A1_PID, ClaimType.CANCEL,
                            ClaimReasonCode.BUYER_CHANGED_MIND, null, USER_ID, LocalDateTime.now()));
                    return null;
                });
            } else {
                works.add(() -> {
                    adminOrderCancelService.cancel(ORDER_A_PID, List.of(ITEM_A1_PID), ClaimReasonCode.ORDER_MISTAKE,
                            "관리자 취소", adminContext);
                    return null;
                });
            }
        }

        List<Throwable> failures = runConcurrently(works);

        assertThat(failures).hasSize(THREADS - 1)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(ClaimInvalidStateException.class));
        assertThat(claimCount(ORDER_A_ITEM_1)).isEqualTo(1);
        assertThat(claimCount(ORDER_A_ITEM_2)).isZero();
    }

    // ===== A: 관리자 취소(결제 후·부분) =====

    @Test
    @DisplayName("T3 관리자 부분 취소(A): 품목 1건 지정 → 200·Claim APPROVED·해당 품목 CANCEL_REQUESTED·타 품목 PAID·Refund 자동 COMPLETED(totalPrice)·CANCELLED·재고 복구·중복 웹훅 멱등")
    void adminPartialCancel_thenRefundWebhook_restoresStock() throws Exception {
        mockMvc.perform(post(URL + "/" + ORDER_A_PID + "/cancel").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"STOCK_DELAY\",\"reasonDetail\":\"입고 지연\",\"orderItemPublicIds\":[\"" + ITEM_A1_PID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER_A_PID))
                .andExpect(jsonPath("$.claims.length()").value(1))
                .andExpect(jsonPath("$.claims[0].orderItemId").value(ITEM_A1_PID))
                .andExpect(jsonPath("$.claims[0].status").value("APPROVED"));

        // Track 80 C4: 승인 → 환불 자동 완료 → 품목 CANCELLED까지 요청 스레드에서 수렴(아래 상세 단언)
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("CANCELLED");
        assertThat(itemStatus(ORDER_A_ITEM_2)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT reason_code FROM claim WHERE order_item_id = ?", String.class, ORDER_A_ITEM_1))
                .isEqualTo("STOCK_DELAY");
        assertThat(jdbc.queryForObject("SELECT requested_by FROM claim WHERE order_item_id = ?", Long.class, ORDER_A_ITEM_1))
                .isEqualTo(ADMIN_ID);
        assertThat(refundCountForItem(ORDER_A_ITEM_1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT r.amount FROM refund r JOIN claim c ON c.id = r.claim_id WHERE c.order_item_id = ?",
                Long.class, ORDER_A_ITEM_1)).isEqualTo(ITEM_PRICE);
        // Track 80 C4: Mock PG 자동 완료 콜백(MockRefundAutoCallbackListener) → Refund COMPLETED → Claim COMPLETED → 품목 CANCELLED·restoreStock
        assertThat(jdbc.queryForObject("SELECT r.status FROM refund r JOIN claim c ON c.id = r.claim_id WHERE c.order_item_id = ?",
                String.class, ORDER_A_ITEM_1)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM claim WHERE order_item_id = ?", String.class, ORDER_A_ITEM_1))
                .isEqualTo("COMPLETED");
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("CANCELLED");
        assertThat(onHand(VARIANT_1)).isEqualTo(11);       // 10 + 1 복구
        assertThat(onHand(VARIANT_2)).isEqualTo(10);       // 타 품목 불변
        assertThat(historyCount(INVENTORY_1, "CANCEL")).isEqualTo(1);

        // 중복 콜백(수동 웹훅 재수신) → RFN-3 멱등 no-op·상태·재고 불변
        String pgRefundId = jdbc.queryForObject(
                "SELECT r.pg_refund_id FROM refund r JOIN claim c ON c.id = r.claim_id WHERE c.order_item_id = ?",
                String.class, ORDER_A_ITEM_1);
        mockMvc.perform(post("/api/webhooks/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"pgRefundId\": \"" + pgRefundId + "\", \"status\": \"SUCCESS\" }"))
                .andExpect(status().isOk());
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("CANCELLED");
        assertThat(onHand(VARIANT_1)).isEqualTo(11);
        assertThat(historyCount(INVENTORY_1, "CANCEL")).isEqualTo(1);

        // Track 96-1 D-202: 부분 환불(10000/20000) → 상세 결제 행 refundedAmount = 환불 합·status PAID 유지(D-71 전액 가드)
        mockMvc.perform(get(URL + "/" + ORDER_A_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].amount").value(20000))
                .andExpect(jsonPath("$.payments[0].refundedAmount").value(ITEM_PRICE));
    }

    @Test
    @DisplayName("T4 배송 시작 후 취소 거절: SHIPPING 품목 관리자 취소 → 422·Claim 없음")
    void adminCancel_shippingItem_rejected() throws Exception {
        mockMvc.perform(post(URL + "/" + ORDER_C_PID + "/cancel").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"ORDER_MISTAKE\",\"orderItemPublicIds\":[\"" + ITEM_C_PID + "\"]}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(claimCount(ORDER_C_ITEM)).isZero();
        assertThat(itemStatus(ORDER_C_ITEM)).isEqualTo("SHIPPING");
    }

    // ===== C: 관리자 취소(미결제) =====

    @Test
    @DisplayName("T5 관리자 미결제 취소(C): PENDING_PAYMENT → 200·PAYMENT_EXPIRED·reserved 해제·audit 사유 기록·상세에 cancelReasons → 재취소 409")
    void adminCancel_unpaid_terminatesAndAudits_thenConflict() throws Exception {
        assertThat(reserved(VARIANT_2)).isEqualTo(1);

        mockMvc.perform(post(URL + "/" + ORDER_B_PID + "/cancel").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"PAYMENT_ISSUE\",\"reasonDetail\":\"입금 미확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PAYMENT_EXPIRED"))
                .andExpect(jsonPath("$.claims.length()").value(0));

        assertThat(orderStatus(ORDER_B)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(reserved(VARIANT_2)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'ORDER' AND target_id = ? "
                + "AND diff_json LIKE ?", Integer.class, ORDER_B, "%PAYMENT_ISSUE%")).isEqualTo(1);

        mockMvc.perform(get(URL + "/" + ORDER_B_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_EXPIRED"))
                .andExpect(jsonPath("$.cancelReasons.length()").value(1))
                .andExpect(jsonPath("$.cancelReasons[0].reasonCode").value("PAYMENT_ISSUE"))
                .andExpect(jsonPath("$.cancelReasons[0].reasonDetail").value("입금 미확인"))
                .andExpect(jsonPath("$.cancelReasons[0].actorUserId").value(ADMIN_ID))
                .andExpect(jsonPath("$.actions.length()").value(0));

        mockMvc.perform(post(URL + "/" + ORDER_B_PID + "/cancel").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"PAYMENT_ISSUE\"}"))
                .andExpect(status().isConflict());
    }

    // ===== 조회 =====

    @Test
    @DisplayName("T6 목록: 필터(status·paymentStatus·deliveryStatus)·검색(주문번호·주문자·상품명)·enrich 필드·쿼리 수 ≤ 8")
    void list_filtersSearchAndEnrich_withBoundedQueries() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "ORDT79" + ORDER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER_A_PID))
                .andExpect(jsonPath("$.items[0].buyerName").value("트랙79구매자"))
                .andExpect(jsonPath("$.items[0].buyerEmail").value("t79@example.test"))
                .andExpect(jsonPath("$.items[0].sellerNames[0]").value("트랙79셀러"))
                .andExpect(jsonPath("$.items[0].productSummary").value("트랙79상품A 외 1건"))
                .andExpect(jsonPath("$.items[0].itemCount").value(2))
                .andExpect(jsonPath("$.items[0].paymentAmount").value(20000))
                .andExpect(jsonPath("$.items[0].paymentMethod").value("CARD"))
                .andExpect(jsonPath("$.items[0].paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.items[0].paidAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].claimInProgress").value(false))
                .andExpect(jsonPath("$.items[0].actions[0]").value("CANCEL"))
                .andExpect(jsonPath("$.items[0].actions[1]").value("PREPARE_SHIPMENT"));

        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("status", "PENDING_PAYMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.orderId == '" + ORDER_B_PID + "')]").exists())
                .andExpect(jsonPath("$.items[?(@.orderId == '" + ORDER_A_PID + "')]").doesNotExist());
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("paymentStatus", "PENDING")
                        .param("keyword", "트랙79구매자"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER_B_PID))
                // 미결제(PENDING 결제 행)는 승인 시각이 없어 paidAt 미노출(NON_NULL)
                .andExpect(jsonPath("$.items[0].paidAt").doesNotExist());
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("deliveryStatus", "SHIPPING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER_C_PID))
                .andExpect(jsonPath("$.items[0].deliveryStatus").value("SHIPPING"))
                .andExpect(jsonPath("$.items[0].actions[0]").value("MARK_DELIVERED"));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "트랙79상품C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER_C_PID));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("sort", "BOGUS"))
                .andExpect(status().isBadRequest());
        // Track 84: buyerPublicId 정확 필터 — 해당 회원 주문 3건 전부·타 회원 미포함 / 미존재 publicId는 빈 페이지(404 아님)
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("buyerPublicId", pid("usr_", "T79USR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[*].buyerEmail", everyItem(is("t79@example.test"))));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("buyerPublicId", pid("usr_", "T79NONE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        // 비BUYER(관리자 계정) publicId → BUYER 해소 실패 → 빈 페이지(외부 검토 반영)
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("buyerPublicId", pid("usr_", "T79ADM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "트랙79구매자").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(QUERY_BUDGET_FOR_LIST);
        statistics.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("T7 상세: 주문자·배송지·결제 이력·항목(배송·클레임 approvable)·actions / 미존재 404")
    void detail_returnsGraph() throws Exception {
        // 사용자 승인형 Claim 1건(REQUESTED) 생성 → approvable=true
        claimService.request(new ClaimRequestCommand(ITEM_A2_PID, ClaimType.CANCEL, ClaimReasonCode.BUYER_CHANGED_MIND,
                "변심", USER_ID, LocalDateTime.now()));

        mockMvc.perform(get(URL + "/" + ORDER_A_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("ORDT79" + ORDER_A))
                .andExpect(jsonPath("$.buyer.name").value("트랙79구매자"))
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("홍길동"))
                .andExpect(jsonPath("$.paymentAmount").value(20000))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].pgTid").value("tid_track79_a")) // Track 89-A: PG 대사용 pgTid·failureCode 노출
                .andExpect(jsonPath("$.payments[0].failureCode").doesNotExist())
                .andExpect(jsonPath("$.payments[0].refundedAmount").value(0)) // Track 96-1 D-202: 환불 없음 → 0
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.orderItemId == '" + ITEM_A2_PID + "')].status").value("CANCEL_REQUESTED"))
                .andExpect(jsonPath("$.items[?(@.orderItemId == '" + ITEM_A2_PID + "')].claims[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.items[?(@.orderItemId == '" + ITEM_A2_PID + "')].claims[0].approvable").value(true))
                .andExpect(jsonPath("$.items[?(@.orderItemId == '" + ITEM_A1_PID + "')].claims.length()").value(0))
                .andExpect(jsonPath("$.cancelReasons.length()").value(0));

        mockMvc.perform(get(URL + "/" + ORDER_C_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].delivery.status").value("SHIPPING"))
                .andExpect(jsonPath("$.items[0].delivery.trackingNo").value("T79TRACK0001"));

        mockMvc.perform(get(URL + "/" + pid("ord_", "T79NONE")).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound());
    }

    // ===== F: 관리자 송장 등록·권한 =====

    @Test
    @DisplayName("T9 목록 전체 반품 표기(Track 103): 전 품목 RETURNED(주문 CONFIRMED) → allItemsReturned true / 일반 주문 false")
    void list_allItemsReturned() throws Exception {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE order_item SET item_status = 'RETURNED' WHERE id = ?", ORDER_C_ITEM);
            jdbc.update("UPDATE `order` SET status = 'CONFIRMED' WHERE id = ?", ORDER_C);
        });

        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "트랙79상품C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER_C_PID))
                .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].allItemsReturned").value(true));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "ORDT79" + ORDER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].allItemsReturned").value(false));
    }

    @Test
    @DisplayName("T8 관리자 송장 등록(F): ADMIN 200·품목 SHIPPING·delivery 생성 / BUYER 403 / 미인증 401 / SHIPPING 품목 재등록 422")
    void adminPrepareShipment_andAuthorization() throws Exception {
        String body = "{\"carrier\":\"CJ\",\"trackingNo\":\"T79TRACK0002\"}";
        mockMvc.perform(post(URL + "/items/" + ITEM_A1_PID + "/prepare-shipment").headers(authHeaders.buyer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/items/" + ITEM_A1_PID + "/prepare-shipment")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).headers(authHeaders.buyer(USER_ID)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(URL + "/items/" + ITEM_A1_PID + "/prepare-shipment").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNo").value("T79TRACK0002"));
        assertThat(itemStatus(ORDER_A_ITEM_1)).isEqualTo("SHIPPING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE order_item_id = ?", Integer.class, ORDER_A_ITEM_1))
                .isEqualTo(1);

        mockMvc.perform(post(URL + "/items/" + ITEM_A1_PID + "/prepare-shipment").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"CJ\",\"trackingNo\":\"T79TRACK0003\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 동시 실행 ----------

    private List<Throwable> runConcurrently(Callable<Void> work) throws InterruptedException {
        List<Callable<Void>> works = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            works.add(work);
        }
        return runConcurrently(works);
    }

    /** 워커들을 latch로 동시 출발시키고 각 워커의 예외(원인)를 모아 반환한다(성공 = 예외 없음). */
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

    // ---------- 시드·helpers ----------

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    private void seedGraph() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "T79USR"), "t79@example.test", "트랙79구매자");
                // Track 84: buyerPublicId 필터는 BUYER role 보유 회원만 해소한다 → BUYER 매핑(V11 seed code 조회) + 비BUYER 관리자 계정 행
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?", USER_ID, "BUYER");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        ADMIN_ID, pid("usr_", "T79ADM"), "t79-admin@example.test", "트랙79관리자");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '트랙79셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "T79SLR"));
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, '트랙79상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "T79PRD"), SELLER_ID, DUMMY_FK_ID);
                seedVariant(VARIANT_1, pid("var_", "T79VAR1"), "VCT79A", 1);
                seedVariant(VARIANT_2, pid("var_", "T79VAR2"), "VCT79B", 2);
                // 주문 A 품목 2건은 결제 완료(예약 확정 후) → reserved 0 / 주문 B(미결제) 품목은 variant 2 예약 1
                seedInventory(INVENTORY_1, VARIANT_1, 10, 0, 10);
                seedInventory(INVENTORY_2, VARIANT_2, 10, 1, 9);

                seedOrder(ORDER_A, ORDER_A_PID, "PAID", 2 * ITEM_PRICE, true);
                seedOrderItem(ORDER_A_ITEM_1, ITEM_A1_PID, ORDER_A, VARIANT_1, "트랙79상품A", "PAID");
                seedOrderItem(ORDER_A_ITEM_2, ITEM_A2_PID, ORDER_A, VARIANT_2, "트랙79상품A-2", "PAID");
                seedPayment(PAYMENT_A, pid("pay_", "T79PAYA"), ORDER_A, 2 * ITEM_PRICE, "PAID", "tid_track79_a", "pat_track79_a");
                seedSnapshot(ORDER_A);

                seedOrder(ORDER_B, ORDER_B_PID, "PENDING_PAYMENT", ITEM_PRICE, false);
                seedOrderItem(ORDER_B_ITEM, ITEM_B_PID, ORDER_B, VARIANT_2, "트랙79상품B", "ORDERED");
                seedPayment(PAYMENT_B, pid("pay_", "T79PAYB"), ORDER_B, ITEM_PRICE, "PENDING", null, "pat_track79_b");

                seedOrder(ORDER_C, ORDER_C_PID, "SHIPPING", ITEM_PRICE, true);
                seedOrderItem(ORDER_C_ITEM, ITEM_C_PID, ORDER_C, VARIANT_1, "트랙79상품C", "SHIPPING");
                jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, carrier, tracking_no, status, shipped_at, "
                                + "created_at, updated_at) VALUES (?, ?, ?, 'CJ', 'T79TRACK0001', 'SHIPPING', NOW(6), NOW(6), NOW(6))",
                        DELIVERY_C, pid("dlv_", "T79DLVC"), ORDER_C_ITEM);
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

    private void seedOrder(long id, String publicId, String status, long totalPrice, boolean paid) {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, NOW(6), ?, NOW(6), NOW(6))",
                id, publicId, USER_ID, "ORDT79" + id, status, totalPrice, paid ? LocalDateTime.now() : null);
    }

    private void seedOrderItem(long id, String publicId, long orderId, long variantId, String productName, String status) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), ?, 1000)",
                id, publicId, orderId, PRODUCT_ID, variantId, SELLER_ID, ITEM_PRICE, ITEM_PRICE, status, productName);
    }

    private void seedPayment(long id, String publicId, long orderId, long amount, String status, String pgTid, String attemptKey) {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, "
                        + "paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, ?, 'MOCK_PG', ?, ?, ?, NOW(6), NOW(6))",
                id, publicId, orderId, amount, status, pgTid, attemptKey, "PAID".equals(status) ? LocalDateTime.now() : null);
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
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'ORDER' AND target_id IN (?, ?, ?)", ORDER_A, ORDER_B, ORDER_C);
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id IN (?, ?, ?, ?))",
                        ORDER_A_ITEM_1, ORDER_A_ITEM_2, ORDER_B_ITEM, ORDER_C_ITEM);
                jdbc.update("DELETE FROM claim WHERE order_item_id IN (?, ?, ?, ?)", ORDER_A_ITEM_1, ORDER_A_ITEM_2, ORDER_B_ITEM, ORDER_C_ITEM);
                jdbc.update("DELETE FROM delivery WHERE order_item_id IN (?, ?, ?, ?)", ORDER_A_ITEM_1, ORDER_A_ITEM_2, ORDER_B_ITEM, ORDER_C_ITEM);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id IN (?, ?)", INVENTORY_1, INVENTORY_2);
                jdbc.update("DELETE FROM inventory WHERE id IN (?, ?)", INVENTORY_1, INVENTORY_2);
                jdbc.update("DELETE FROM payment WHERE order_id IN (?, ?, ?)", ORDER_A, ORDER_B, ORDER_C);
                jdbc.update("DELETE FROM order_shipping_snapshot WHERE order_id IN (?, ?, ?)", ORDER_A, ORDER_B, ORDER_C);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (?, ?, ?)", ORDER_A, ORDER_B, ORDER_C);
                jdbc.update("DELETE FROM `order` WHERE id IN (?, ?, ?)", ORDER_A, ORDER_B, ORDER_C);
                jdbc.update("DELETE FROM product_variant WHERE id IN (?, ?)", VARIANT_1, VARIANT_2);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", USER_ID, ADMIN_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int claimCount(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM claim WHERE order_item_id = ?", Integer.class, orderItemId);
    }

    private int refundCountForItem(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund r JOIN claim c ON c.id = r.claim_id WHERE c.order_item_id = ?",
                Integer.class, orderItemId);
    }

    private String itemStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, orderItemId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, orderId);
    }

    private int reserved(long variantId) {
        return jdbc.queryForObject("SELECT quantity_reserved FROM inventory WHERE variant_id = ?", Integer.class, variantId);
    }

    private int onHand(long variantId) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE variant_id = ?", Integer.class, variantId);
    }

    private int historyCount(long inventoryId, String changeType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ? AND change_type = ?",
                Integer.class, inventoryId, changeType);
    }

    /** prefix + 26자 본문(tag 대문자 + '0' 패딩·[0-9A-Z])으로 30자 public_id를 만든다(@Pattern 정합). */
    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

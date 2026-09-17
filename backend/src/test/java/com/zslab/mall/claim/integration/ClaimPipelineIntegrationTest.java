package com.zslab.mall.claim.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.payment.gateway.MockPaymentGateway;
import com.zslab.mall.refund.enums.RefundCallbackStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.refund.scheduler.MockRefundPendingRecoveryScheduler;
import com.zslab.mall.refund.scheduler.RefundRecoveryScheduler;
import com.zslab.mall.refund.service.RefundRecoveryService;
import com.zslab.mall.refund.service.RefundService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 클레임 파이프라인 정합성 통합 테스트(D-172·외부 검토 A·실 MariaDB·실 MockPaymentGateway). 동시 initiate·동시 콜백·복구 스케줄러·
 * 재고 복구 실패 롤백·검수 FAIL 원복 실패 롤백·첨부 동시 재사용을 실제 커밋·행 락으로 검증한다.
 *
 * <p>스케줄러 자동 발화는 컨텍스트에서 끄고(킬스위치 검증 겸) 배치를 직접 생성해 호출한다. 클래스 {@code @Transactional} 없음(락·경합 검증).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.order.auto-cancel.enabled=false",
        "zslab.order.expired-cleanup.enabled=false",
        "zslab.order.auto-confirm.enabled=false",
        "zslab.refund.recovery.enabled=false"
})
class ClaimPipelineIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9502L;
    private static final long SELLER_ID = 9502L;
    private static final long SELLER_USER_ID = 9504L;
    private static final long PRODUCT_ID = 9502L;
    private static final long VARIANT_ID = 9502L;
    private static final long DUMMY_FK_ID = 9502L;
    private static final long ORDER_ID = 9502L;
    private static final long ITEM_A = 9502L;
    private static final long ITEM_B = 9503L;
    private static final long PAYMENT_ID = 9502L;
    private static final long CLAIM_ID = 9502L;
    private static final long REFUND_ID = 9502L;
    private static final long ITEM_PRICE = 10_000L;
    private static final int ON_HAND = 5;
    private static final int THREADS = 4;
    private static final String PG_REFUND_ID = "mock_rfn_pipeline_9502";
    private static final String CLAIMS_URL = "/api/v1/claims";

    @MockitoBean
    private SmsSender smsSender;
    @MockitoSpyBean
    private MockPaymentGateway paymentGateway;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundRecoveryService refundRecoveryService;
    @Autowired
    private ClaimRepository claimRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private ApplicationContext applicationContext;
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
        seed(() -> {
            seedCatalog();
            seedOrder();
            seedPayment();
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ===== 환불 멱등·동시성(Q2-b·c) =====

    @Test
    @DisplayName("T1 동시 initiate(자동 핸들러 경로 + 복구 스케줄러 경로 4스레드) → 환불 1건·PG 호출 1회·클레임 COMPLETED(Mock 콜백)")
    void concurrentInitiate_singleRefundSinglePgCall() throws Exception {
        seed(() -> {
            seedOrderItem(ITEM_A, "CANCEL_REQUESTED");
            seedClaim(CLAIM_ID, ITEM_A, "CANCEL", "APPROVED", "PAID", LocalDateTime.now().minusMinutes(1));
        });

        List<Callable<String>> workers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final boolean recovery = i % 2 == 0;
            workers.add(() -> {
                if (recovery) {
                    refundRecoveryService.recoverMissingRefund(CLAIM_ID);
                } else {
                    refundService.initiate(CLAIM_ID, ITEM_PRICE);
                }
                return "OK";
            });
        }
        runConcurrently(workers);

        assertThat(refundCount(CLAIM_ID)).isEqualTo(1);
        verify(paymentGateway, times(1)).refund(any(), anyLong());
        // Mock 자동 콜백(AFTER_COMMIT)은 뒤따르는 initiate 대기 스레드와 락 경합으로 실패(PENDING 잔류·흡수)할 수 있다 — 그 경우가
        // 바로 복구 스케줄러(Mock PENDING 재발생) 대상이다. 여기서는 콜백 재발생으로 수렴을 확인한다(PG 호출은 여전히 1회).
        String pgRefundId = jdbc.queryForObject("SELECT pg_refund_id FROM refund WHERE claim_id = ?", String.class, CLAIM_ID);
        refundService.handleCallback(pgRefundId, RefundCallbackStatus.SUCCESS, null);
        verify(paymentGateway, times(1)).refund(any(), anyLong());
        assertThat(refundStatus(CLAIM_ID)).isEqualTo("COMPLETED");
        assertThat(claimStatus(CLAIM_ID)).isEqualTo("COMPLETED");
        assertThat(itemStatus(ITEM_A)).isEqualTo("CANCELLED");
        assertThat(onHand()).isEqualTo(ON_HAND + 1);
    }

    @Test
    @DisplayName("T2 동시 SUCCESS 콜백 4스레드(웹훅) → COMPLETED 전이 1회·ClaimCompleted 1회(품목 CANCELLED)·재고 복구 1회·history 1")
    void concurrentSuccessCallback_completesOnce() throws Exception {
        seed(() -> {
            seedOrderItem(ITEM_A, "CANCEL_REQUESTED");
            seedClaim(CLAIM_ID, ITEM_A, "CANCEL", "APPROVED", "PAID", LocalDateTime.now().minusMinutes(1));
            seedRefund(REFUND_ID, CLAIM_ID, "PENDING", PG_REFUND_ID, 0);
        });

        List<Callable<String>> workers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            workers.add(() -> String.valueOf(mockMvc.perform(post("/api/webhooks/refunds").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pgRefundId\":\"" + PG_REFUND_ID + "\",\"status\":\"SUCCESS\"}"))
                    .andReturn().getResponse().getStatus()));
        }
        List<String> statuses = runConcurrently(workers);

        assertThat(statuses).containsOnly("200"); // 늦은 콜백은 종결 no-op(RFN-3)
        assertThat(refundStatus(CLAIM_ID)).isEqualTo("COMPLETED");
        assertThat(claimStatus(CLAIM_ID)).isEqualTo("COMPLETED");
        assertThat(itemStatus(ITEM_A)).isEqualTo("CANCELLED");
        assertThat(onHand()).isEqualTo(ON_HAND + 1);
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T3 재고 복구 실패(inventory 행 없음) → 콜백 422·Refund PENDING·Claim APPROVED·품목 유지(동기 체인 롤백)")
    void callbackRollsBack_whenInventoryRestoreFails() throws Exception {
        seed(() -> {
            seedOrderItem(ITEM_A, "CANCEL_REQUESTED");
            seedClaim(CLAIM_ID, ITEM_A, "CANCEL", "APPROVED", "PAID", LocalDateTime.now().minusMinutes(1));
            seedRefund(REFUND_ID, CLAIM_ID, "PENDING", PG_REFUND_ID, 0);
            jdbc.update("DELETE FROM inventory WHERE id = ?", VARIANT_ID);
        });

        mockMvc.perform(post("/api/webhooks/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pgRefundId\":\"" + PG_REFUND_ID + "\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(refundStatus(CLAIM_ID)).isEqualTo("PENDING");
        assertThat(claimStatus(CLAIM_ID)).isEqualTo("APPROVED");
        assertThat(itemStatus(ITEM_A)).isEqualTo("CANCEL_REQUESTED");

        // 재고 행 복구 후 재전송 → 정상 수렴(PG 재전송 시나리오)
        seed(() -> seedInventory());
        mockMvc.perform(post("/api/webhooks/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pgRefundId\":\"" + PG_REFUND_ID + "\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        assertThat(claimStatus(CLAIM_ID)).isEqualTo("COMPLETED");
        assertThat(onHand()).isEqualTo(ON_HAND + 1);
    }

    // ===== 복구 스케줄러(Q2-a) =====

    @Test
    @DisplayName("T4 복구 스케줄러: 5분 경과 환불 누락 CANCEL·RETURN PASS → initiate / 5분 미만·FAILED 보유 제외 / Mock PENDING 5분 경과 → 완료 / 킬스위치 빈 부재")
    void recoveryScheduler_missingAndPendingRefunds() {
        assertThat(applicationContext.getBeanNamesForType(RefundRecoveryScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(MockRefundPendingRecoveryScheduler.class)).isEmpty();
        RefundRecoveryScheduler scheduler = new RefundRecoveryScheduler(claimRepository, refundRecoveryService);
        MockRefundPendingRecoveryScheduler mockScheduler = new MockRefundPendingRecoveryScheduler(refundRepository, refundRecoveryService);

        long missingCancel = CLAIM_ID;          // 승인 10분 전·환불 없음 → 대상
        long recentCancel = CLAIM_ID + 1;       // 승인 직후 → 유예 중·제외
        long failedCancel = CLAIM_ID + 2;       // FAILED 행 보유 → 제외(관리자 재시도 경로)
        long passedReturn = CLAIM_ID + 3;       // 검수 PASS 10분 전·환불 없음 → 대상
        long pendingMock = CLAIM_ID + 4;        // PENDING 10분 전(pg id 보유) → Mock 콜백 재발생
        seed(() -> {
            seedOrderItem(ITEM_A, "CANCEL_REQUESTED");
            seedOrderItem(ITEM_B, "RETURN_REQUESTED");
            seedClaim(missingCancel, ITEM_A, "CANCEL", "APPROVED", "PAID", LocalDateTime.now().minusMinutes(10));
            seedClaim(recentCancel, ITEM_A, "CANCEL", "APPROVED", "PAID", LocalDateTime.now());
            seedClaim(failedCancel, ITEM_A, "CANCEL", "APPROVED", "PAID", LocalDateTime.now().minusMinutes(10));
            seedRefund(REFUND_ID, failedCancel, "FAILED", null, 10);
            seedClaim(passedReturn, ITEM_B, "RETURN", "APPROVED", "DELIVERED", LocalDateTime.now().minusMinutes(10));
            jdbc.update("UPDATE claim SET picked_up_at = ?, inspected_at = ?, inspection_result = 'PASS', restock = 0 WHERE id = ?",
                    LocalDateTime.now().minusMinutes(11), LocalDateTime.now().minusMinutes(10), passedReturn);
            seedClaim(pendingMock, ITEM_A, "CANCEL", "APPROVED", "PAID", LocalDateTime.now().minusMinutes(10));
            seedRefund(REFUND_ID + 1, pendingMock, "PENDING", PG_REFUND_ID, 10);
        });

        scheduler.recoverBatch();

        assertThat(refundCount(missingCancel)).isEqualTo(1);
        assertThat(refundCount(passedReturn)).isEqualTo(1);
        assertThat(refundCount(recentCancel)).isZero();
        assertThat(refundCount(failedCancel)).isEqualTo(1); // FAILED 1건 그대로·신규 없음
        assertThat(refundStatus(failedCancel)).isEqualTo("FAILED");
        assertThat(refundStatus(missingCancel)).isEqualTo("COMPLETED"); // Mock 자동 콜백
        assertThat(claimStatus(missingCancel)).isEqualTo("COMPLETED");
        assertThat(claimStatus(passedReturn)).isEqualTo("COMPLETED");
        assertThat(itemStatus(ITEM_B)).isEqualTo("RETURNED");

        assertThat(refundStatus(pendingMock)).isEqualTo("PENDING");
        mockScheduler.replayBatch();
        assertThat(refundStatus(pendingMock)).isEqualTo("COMPLETED");
        assertThat(claimStatus(pendingMock)).isEqualTo("COMPLETED");
        // 재실행 멱등: 대상 없음·환불 수 불변
        scheduler.recoverBatch();
        mockScheduler.replayBatch();
        assertThat(refundCount(missingCancel)).isEqualTo(1);
        assertThat(refundCount(pendingMock)).isEqualTo(1);
    }

    // ===== 검수 FAIL 봉인(Q1) =====

    @Test
    @DisplayName("T5 검수 FAIL: 사유 OTHER → 400·상태 불변 / 품목 원복 실패(스냅샷 불법 전이) → 요청 실패·클레임 APPROVED·재발송 없음(전체 롤백)")
    void inspectFail_reasonSealed_andRollbackOnRestoreFailure() throws Exception {
        seed(() -> {
            seedOrderItem(ITEM_A, "RETURN_REQUESTED");
            // previous_order_item_status=PAID: RETURN_REQUESTED → PAID는 불법 전이라 원복 핸들러(동기)가 실패한다
            seedClaim(CLAIM_ID, ITEM_A, "RETURN", "APPROVED", "PAID", LocalDateTime.now().minusMinutes(1));
            jdbc.update("UPDATE claim SET picked_up_at = ? WHERE id = ?", LocalDateTime.now(), CLAIM_ID);
        });
        String claimPid = claimPid(CLAIM_ID);

        mockMvc.perform(post("/api/v1/admin/claims/" + claimPid + "/inspect").headers(authHeaders.admin(9500L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"FAIL\",\"rejectReasonCode\":\"OTHER\",\"memo\":\"우회\",\"reshipCarrier\":\"CJ\",\"reshipTrackingNo\":\"X-1\"}"))
                .andExpect(status().isBadRequest());
        assertThat(claimStatus(CLAIM_ID)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT inspection_result FROM claim WHERE id = ?", String.class, CLAIM_ID)).isNull();

        int restoreFailStatus = mockMvc.perform(post("/api/v1/admin/claims/" + claimPid + "/inspect").headers(authHeaders.admin(9500L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"FAIL\",\"rejectReasonCode\":\"INSPECTION_FAILED\",\"reshipCarrier\":\"CJ\",\"reshipTrackingNo\":\"X-2\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(restoreFailStatus).isGreaterThanOrEqualTo(400);
        assertThat(claimStatus(CLAIM_ID)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT inspection_result FROM claim WHERE id = ?", String.class, CLAIM_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE claim_id = ?", Integer.class, CLAIM_ID)).isZero();
        assertThat(itemStatus(ITEM_A)).isEqualTo("RETURN_REQUESTED");
    }

    // ===== 첨부 동시 재사용(Q5) =====

    @Test
    @DisplayName("T6 같은 첨부로 서로 다른 품목 반품 요청 동시 4스레드 → 1건만 201·나머지 400·클레임 1건·첨부 target 1건")
    void concurrentAttachmentReuse_onlyOneClaim() throws Exception {
        long attachmentId = 9502L;
        String attachmentPid = "att_" + ("PIPELINEATT" + "00000000000000000000000000").substring(0, 26);
        seed(() -> {
            seedOrderItem(ITEM_A, "DELIVERED");
            seedOrderItem(ITEM_B, "DELIVERED");
            seedOutboundDelivered(9502L, ITEM_A);
            seedOutboundDelivered(9503L, ITEM_B);
            jdbc.update("INSERT INTO attachment (id, public_id, target_type, target_id, file_name, file_path, mime_type, file_size, "
                            + "display_order, uploaded_by, created_at, updated_at) VALUES (?, ?, 'CLAIM', NULL, 'p.png', "
                            + "'/api/v1/files/claims/2026/09/PIPELINE.png', 'image/png', 10, 0, ?, NOW(6), NOW(6))",
                    attachmentId, attachmentPid, USER_ID);
        });

        List<Callable<String>> workers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final long itemId = i % 2 == 0 ? ITEM_A : ITEM_B;
            workers.add(() -> String.valueOf(mockMvc.perform(post(CLAIMS_URL).headers(authHeaders.buyer(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderItemPublicId\":\"" + itemPid(itemId) + "\",\"claimType\":\"RETURN\",\"reasonCode\":\"PRODUCT_DEFECT\","
                                    + "\"attachmentIds\":[\"" + attachmentPid + "\"]}"))
                    .andReturn().getResponse().getStatus()));
        }
        List<String> statuses = runConcurrently(workers);

        assertThat(statuses.stream().filter("201"::equals).count()).isEqualTo(1L);
        assertThat(statuses.stream().filter(s -> !"201".equals(s)).allMatch(s -> "400".equals(s) || "422".equals(s))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM claim WHERE order_item_id IN (?, ?)", Long.class, ITEM_A, ITEM_B)).isEqualTo(1L);
        Long targetId = jdbc.queryForObject("SELECT target_id FROM attachment WHERE id = ?", Long.class, attachmentId);
        assertThat(targetId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM claim WHERE id = ?", Long.class, targetId)).isEqualTo(1L);
        // 실패한 품목은 요청 상태로 전이되지 않았다(클레임 INSERT까지 롤백)
        long requestedItems = jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE id IN (?, ?) AND item_status = 'RETURN_REQUESTED'",
                Long.class, ITEM_A, ITEM_B);
        assertThat(requestedItems).isEqualTo(1L);
    }

    // ---------- helpers ----------

    private List<String> runConcurrently(List<Callable<String>> workers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers.size());
        CountDownLatch ready = new CountDownLatch(workers.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> worker : workers) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    return worker.call();
                } catch (RuntimeException exception) {
                    return exception.getClass().getSimpleName();
                }
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

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

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(시각 표현식은 테스트 내부 상수·SQL injection 위험 없음).

    private void seedCatalog() {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                USER_ID, pid("usr_", "PIPUSR"), "pipeline@example.test", "파이프라인구매자", "010-5555-6666");
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '파이프라인셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID, pid("slr_", "PIPSLR"));
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", SELLER_USER_ID, SELLER_ID);
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '파이프라인상품', 'SALE', 10000, NOW(6), NOW(6))", PRODUCT_ID, pid("prd_", "PIPPRD"), SELLER_ID, DUMMY_FK_ID);
        jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                        + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VCPIP', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))", VARIANT_ID, pid("var_", "PIPVAR"), PRODUCT_ID, DUMMY_FK_ID);
        seedInventory();
    }

    private void seedInventory() {
        jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))", VARIANT_ID, VARIANT_ID, ON_HAND, ON_HAND);
    }

    private void seedOrder() {
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))", ORDER_ID, pid("ord_", "PIPORD"), USER_ID, "ORDPIP" + ORDER_ID, ITEM_PRICE);
    }

    private void seedOrderItem(long itemId, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                        + "item_status, created_at, updated_at, product_name) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(6), NOW(6), '파이프라인 상품')",
                itemId, itemPid(itemId), ORDER_ID, PRODUCT_ID, VARIANT_ID, SELLER_ID, ITEM_PRICE, ITEM_PRICE, itemStatus);
    }

    private void seedPayment() {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, pg_provider, pg_tid, payment_attempt_key, "
                        + "paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, 'PAID', 'MOCK_PG', 'tid_pip_0001', 'pat_pip_0001', NOW(6), NOW(6), NOW(6))",
                PAYMENT_ID, pid("pay_", "PIPPAY"), ORDER_ID, ITEM_PRICE * 10);
    }

    /**
     * 시각은 JVM {@link LocalDateTime}으로 바인딩한다 — 스케줄러 threshold가 JVM 시각이라 DB NOW()(세션 타임존 상이)를 섞으면 유예 판정이 어긋난다.
     */
    private void seedClaim(long claimId, long itemId, String type, String status, String previousStatus, LocalDateTime processedAt) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, status, reason_code, requested_by, requested_at, processed_at, "
                        + "previous_order_item_status, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'PRODUCT_DEFECT', ?, ?, ?, ?, 0, NOW(6), NOW(6))",
                claimId, pid("clm_", "PIPCLM" + claimId), itemId, type, status, USER_ID, LocalDateTime.now().minusHours(1), processedAt, previousStatus);
    }

    private void seedRefund(long refundId, long claimId, String status, String pgRefundId, int minutesAgo) {
        jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(6))",
                refundId, pid("rfn_", "PIPRFN" + refundId), claimId, PAYMENT_ID, ITEM_PRICE, status, pgRefundId, LocalDateTime.now().minusMinutes(minutesAgo));
    }

    private void seedOutboundDelivered(long deliveryId, long itemId) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, 'DELIVERED', NOW(6) - INTERVAL 2 DAY, NOW(6) - INTERVAL 1 DAY, NOW(6), NOW(6))",
                deliveryId, pid("dlv_", "PIPDLV" + deliveryId), itemId, "PIP-TRACK-" + deliveryId);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", USER_ID);
                jdbc.update("DELETE FROM attachment WHERE uploaded_by = ?", USER_ID);
                jdbc.update("DELETE FROM refund WHERE claim_id IN (SELECT id FROM claim WHERE order_item_id IN (?, ?))", ITEM_A, ITEM_B);
                jdbc.update("DELETE FROM delivery WHERE order_item_id IN (?, ?)", ITEM_A, ITEM_B);
                jdbc.update("DELETE FROM claim WHERE order_item_id IN (?, ?)", ITEM_A, ITEM_B);
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM payment WHERE id = ?", PAYMENT_ID);
                jdbc.update("DELETE FROM order_item WHERE id IN (?, ?)", ITEM_A, ITEM_B);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", SELLER_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int refundCount(long claimId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE claim_id = ?", Integer.class, claimId);
    }

    private String refundStatus(long claimId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE claim_id = ? ORDER BY id DESC LIMIT 1", String.class, claimId);
    }

    private String claimStatus(long claimId) {
        return jdbc.queryForObject("SELECT status FROM claim WHERE id = ?", String.class, claimId);
    }

    private String claimPid(long claimId) {
        return jdbc.queryForObject("SELECT public_id FROM claim WHERE id = ?", String.class, claimId);
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT item_status FROM order_item WHERE id = ?", String.class, itemId);
    }

    private int onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, VARIANT_ID);
    }

    private int historyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ?", Integer.class, VARIANT_ID);
    }

    private static String itemPid(long itemId) {
        return pid("oit_", "PIPOIT" + itemId);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

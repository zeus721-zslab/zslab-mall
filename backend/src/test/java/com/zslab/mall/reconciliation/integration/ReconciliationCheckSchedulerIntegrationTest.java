package com.zslab.mall.reconciliation.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.reconciliation.repository.ReconciliationCandidate;
import com.zslab.mall.reconciliation.scheduler.ReconciliationCheckScheduler;
import com.zslab.mall.reconciliation.service.ReconciliationCheckPattern;
import com.zslab.mall.reconciliation.service.ReconciliationCheckService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 불일치 점검 스케줄러 통합 테스트(Track 104-2 D-216·invariants P6·실 MariaDB). 패턴별 1건씩 시드한 뒤 배치를 돌려 패턴마다 불일치 1행이
 * 기록되는지, 재실행이 행을 늘리지 않는지(멱등), 업무 데이터(결제·품목·클레임·배송·환불 상태)를 바꾸지 않는지, 후보가 기록 직전에 풀리면
 * 기록하지 않는지 본다. 스케줄러 빈은 테스트 기본 설정에서 꺼져 있으므로({@code AbstractIntegrationTest}) 직접 생성해 호출한다.
 *
 * <p>배치는 DB 전체를 훑으므로 다른 테스트 잔여 행에도 기록할 수 있다 — 실행 전 최대 id를 잡아 두고 정리 때 그 뒤 행을 모두 지운다.
 */
class ReconciliationCheckSchedulerIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 6601L;
    private static final long AMOUNT = 10_000L;

    // U5: 결제 CANCELLED·환불·클레임 없음
    private static final long U5_ORDER_ID = 6601L;
    private static final long U5_PAYMENT_ID = 6601L;
    // U6: 전액 환불 완료·결제 PAID·확정 품목 없음
    private static final long U6_ORDER_ID = 6602L;
    private static final long U6_PAYMENT_ID = 6602L;
    private static final long U6_CLAIM_ID = 6602L;
    // U7: 전액 환불 완료·확정 품목 있음(결제 CANCELLED)
    private static final long U7_ORDER_ID = 6603L;
    private static final long U7_PAYMENT_ID = 6603L;
    private static final long U7_CLAIM_ID = 6603L;
    // U10: 클레임 COMPLETED·품목 RETURN_REQUESTED
    private static final long U10_ORDER_ID = 6604L;
    private static final long U10_CLAIM_ID = 6604L;
    // U11: 클레임 REJECTED·품목 CANCEL_REQUESTED(대조: 같은 품목에 진행 중 클레임이 있으면 기록 안 함)
    private static final long U11_ORDER_ID = 6605L;
    private static final long U11_CLAIM_ID = 6605L;
    private static final long U11_CONTROL_REJECTED_CLAIM_ID = 6606L;
    private static final long U11_CONTROL_ACTIVE_CLAIM_ID = 6607L;
    // U12: 원 발송 SHIPPING·품목 PREPARING
    private static final long U12_ORDER_ID = 6608L;
    private static final long U12_DELIVERY_ID = 6608L;
    // U13: 원 발송 DELIVERED·품목 SHIPPING
    private static final long U13_ORDER_ID = 6609L;
    private static final long U13_DELIVERY_ID = 6609L;

    // 품목 id는 주문 id와 같게 둔다(주문당 첫 품목). 아래 둘은 같은 주문의 추가 품목이다.
    private static final long U7_CONFIRMED_ITEM_ID = 6610L;
    private static final long U11_CONTROL_ITEM_ID = 6611L;

    @Autowired
    private ReconciliationCheckService reconciliationCheckService;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private long baselineIssueId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        baselineIssueId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM reconciliation_issue", Long.class);
        seed();
    }

    @AfterEach
    void tearDown() {
        try {
            cleanup();
            tx.executeWithoutResult(s -> jdbc.update("DELETE FROM reconciliation_issue WHERE id > ?", baselineIssueId));
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    @DisplayName("테스트 기본 설정은 점검 스케줄러 빈을 만들지 않는다(킬스위치 false)")
    void schedulerBean_disabledInTests() {
        assertThat(applicationContext.getBeanProvider(ReconciliationCheckScheduler.class).getIfAvailable()).isNull();
    }

    @Test
    @DisplayName("패턴 7종(U5·U6·U7·U10~U13) 각 1행 기록 · 진행 중 클레임 있는 거부 클레임은 제외 · 재실행 멱등 · 업무 데이터 무변경")
    void checkBatch_recordsEachPatternOnce_idempotent_withoutTouchingBusinessData() {
        String before = businessSnapshot();
        ReconciliationCheckScheduler scheduler = new ReconciliationCheckScheduler(reconciliationCheckService);

        scheduler.checkBatch();

        assertThat(issueCount("PAYMENT_CANCELLED_WITHOUT_REFUND", "payment:" + U5_PAYMENT_ID)).isEqualTo(1);
        assertThat(issueCount("FULL_REFUND_PAYMENT_NOT_CANCELLED", "payment:" + U6_PAYMENT_ID)).isEqualTo(1);
        assertThat(issueCount("FULL_REFUND_WITH_CONFIRMED_ITEM", "payment:" + U7_PAYMENT_ID)).isEqualTo(1);
        assertThat(issueCount("ITEM_STATE_DRIFT", "claim-completed:" + U10_CLAIM_ID)).isEqualTo(1);
        assertThat(issueCount("ITEM_STATE_DRIFT", "claim-rejected:" + U11_CLAIM_ID)).isEqualTo(1);
        assertThat(issueCount("ITEM_STATE_DRIFT", "claim-rejected:" + U11_CONTROL_REJECTED_CLAIM_ID)).isZero();
        assertThat(issueCount("ITEM_STATE_DRIFT", "delivery-shipping:" + U12_DELIVERY_ID)).isEqualTo(1);
        assertThat(issueCount("ITEM_STATE_DRIFT", "delivery-delivered:" + U13_DELIVERY_ID)).isEqualTo(1);
        // U5·U6·U7은 한 주문이 다른 패턴에 겹치지 않는다(U6은 확정 품목 없음·U7은 있음·U5는 완료 환불 없음)
        assertThat(ownIssueCount()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT order_id FROM reconciliation_issue WHERE dedupe_key = ?", Long.class,
                "delivery-delivered:" + U13_DELIVERY_ID)).isEqualTo(U13_ORDER_ID);
        assertThat(jdbc.queryForObject("SELECT JSON_VALUE(detail, '$.reason') FROM reconciliation_issue WHERE dedupe_key = ?",
                String.class, "claim-completed:" + U10_CLAIM_ID)).isEqualTo("CLAIM_COMPLETED_ITEM_NOT_TRANSITIONED");

        scheduler.checkBatch();

        assertThat(ownIssueCount()).isEqualTo(7);
        assertThat(businessSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("후보 조회 뒤 기록 직전에 풀린 불일치는 건별 재확인(같은 조회를 대상 1건으로)에서 걸러져 기록되지 않는다 — 순차 재현·동시성 아님")
    void recordIfStillPresent_skipsCandidateResolvedAfterQuery() {
        List<ReconciliationCandidate> candidates =
                reconciliationCheckService.findCandidates(ReconciliationCheckPattern.SHIPPED_ITEM_NOT_TRANSITIONED, 0L, 1000);
        ReconciliationCandidate target = candidates.stream()
                .filter(candidate -> candidate.getTargetId() == U12_DELIVERY_ID).findFirst().orElseThrow();

        sql("UPDATE order_item SET item_status = 'SHIPPING' WHERE id = ?", U12_ORDER_ID);

        assertThat(reconciliationCheckService.recordIfStillPresent(ReconciliationCheckPattern.SHIPPED_ITEM_NOT_TRANSITIONED, target))
                .isFalse();
        assertThat(issueCount("ITEM_STATE_DRIFT", "delivery-shipping:" + U12_DELIVERY_ID)).isZero();
    }

    // ---------- 시드·정리(모든 변수 ? 바인딩) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (long orderId = U5_ORDER_ID; orderId <= U13_ORDER_ID; orderId++) {
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6), NOW(6))",
                        orderId, "ord_rcs_" + orderId, BUYER_ID, "ORDRCS-" + orderId, AMOUNT);
            }
            seedItem(U5_ORDER_ID, U5_ORDER_ID, "DELIVERED");
            seedItem(U6_ORDER_ID, U6_ORDER_ID, "CANCELLED");
            seedItem(U7_ORDER_ID, U7_ORDER_ID, "CANCELLED");
            seedItem(U7_CONFIRMED_ITEM_ID, U7_ORDER_ID, "CONFIRMED");
            seedItem(U10_ORDER_ID, U10_ORDER_ID, "RETURN_REQUESTED");
            seedItem(U11_ORDER_ID, U11_ORDER_ID, "CANCEL_REQUESTED");
            seedItem(U11_CONTROL_ITEM_ID, U11_ORDER_ID, "CANCEL_REQUESTED");
            seedItem(U12_ORDER_ID, U12_ORDER_ID, "PREPARING");
            seedItem(U13_ORDER_ID, U13_ORDER_ID, "SHIPPING");

            seedPayment(U5_PAYMENT_ID, U5_ORDER_ID, "CANCELLED");
            seedPayment(U6_PAYMENT_ID, U6_ORDER_ID, "PAID");
            seedPayment(U7_PAYMENT_ID, U7_ORDER_ID, "CANCELLED");

            seedClaim(U6_CLAIM_ID, U6_ORDER_ID, "CANCEL", "COMPLETED");
            seedCompletedRefund(U6_CLAIM_ID, U6_CLAIM_ID, U6_PAYMENT_ID);
            seedClaim(U7_CLAIM_ID, U7_ORDER_ID, "CANCEL", "COMPLETED");
            seedCompletedRefund(U7_CLAIM_ID, U7_CLAIM_ID, U7_PAYMENT_ID);
            seedClaim(U10_CLAIM_ID, U10_ORDER_ID, "RETURN", "COMPLETED");
            seedClaim(U11_CLAIM_ID, U11_ORDER_ID, "CANCEL", "REJECTED");
            seedClaim(U11_CONTROL_REJECTED_CLAIM_ID, U11_CONTROL_ITEM_ID, "CANCEL", "REJECTED");
            seedClaim(U11_CONTROL_ACTIVE_CLAIM_ID, U11_CONTROL_ITEM_ID, "CANCEL", "REQUESTED");

            seedOutboundDelivery(U12_DELIVERY_ID, U12_ORDER_ID, "SHIPPING");
            seedOutboundDelivery(U13_DELIVERY_ID, U13_ORDER_ID, "DELIVERED");
        });
    }

    private void seedItem(long itemId, long orderId, String itemStatus) {
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
                + "item_status, created_at, updated_at, product_name, commission_rate) VALUES (?, ?, ?, 1, 1, 1, 1, ?, ?, ?, NOW(6), NOW(6), "
                + "'점검 상품', 1000)", itemId, "oit_rcs_" + itemId, orderId, AMOUNT, AMOUNT, itemStatus);
    }

    private void seedPayment(long paymentId, long orderId, String status) {
        jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, pg_provider, pg_tid, "
                + "paid_at, created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, ?, ?, 'MOCK_PG', ?, NOW(6), NOW(6), NOW(6))",
                paymentId, "pay_rcs_" + paymentId, orderId, AMOUNT, status, "pat_rcs_" + paymentId, "tid_rcs_" + paymentId);
    }

    private void seedClaim(long claimId, long itemId, String type, String status) {
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, requested_at, "
                + "previous_order_item_status, version, created_at, updated_at) VALUES (?, ?, ?, ?, 'BUYER_CHANGED_MIND', ?, ?, NOW(6), "
                + "'DELIVERED', 0, NOW(6), NOW(6))", claimId, "clm_rcs_" + claimId, itemId, type, status, BUYER_ID);
    }

    private void seedCompletedRefund(long refundId, long claimId, long paymentId) {
        jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, refunded_at, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, NOW(6), NOW(6), NOW(6))",
                refundId, "rfn_rcs_" + refundId, claimId, paymentId, AMOUNT, "rcs_rfn_" + refundId);
    }

    private void seedOutboundDelivery(long deliveryId, long itemId, String status) {
        jdbc.update("INSERT INTO delivery (id, public_id, order_item_id, direction, carrier, tracking_no, status, shipped_at, delivered_at, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'OUTBOUND', 'CJ', ?, ?, NOW(6) - INTERVAL 2 DAY, "
                + "IF(? = 'DELIVERED', NOW(6), NULL), NOW(6), NOW(6))",
                deliveryId, "dlv_rcs_" + deliveryId, itemId, "RCS-TRACK-" + deliveryId, status, status);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM reconciliation_issue WHERE order_id BETWEEN ? AND ?", U5_ORDER_ID, U13_ORDER_ID);
            jdbc.update("DELETE FROM delivery WHERE id IN (?, ?)", U12_DELIVERY_ID, U13_DELIVERY_ID);
            jdbc.update("DELETE FROM refund WHERE id IN (?, ?)", U6_CLAIM_ID, U7_CLAIM_ID);
            jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", U6_CLAIM_ID, U11_CONTROL_ACTIVE_CLAIM_ID);
            jdbc.update("DELETE FROM payment WHERE id IN (?, ?, ?)", U5_PAYMENT_ID, U6_PAYMENT_ID, U7_PAYMENT_ID);
            jdbc.update("DELETE FROM order_item WHERE order_id BETWEEN ? AND ?", U5_ORDER_ID, U13_ORDER_ID);
            jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", U5_ORDER_ID, U13_ORDER_ID);
        });
    }

    private void sql(String statement, Object... args) {
        tx.executeWithoutResult(s -> jdbc.update(statement, args));
    }

    private int issueCount(String type, String dedupeKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_issue WHERE issue_type = ? AND dedupe_key = ? AND status = 'OPEN'",
                Integer.class, type, dedupeKey);
    }

    /** 이 테스트 시드 주문에 붙은 불일치 행 수. */
    private int ownIssueCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_issue WHERE order_id BETWEEN ? AND ?",
                Integer.class, U5_ORDER_ID, U13_ORDER_ID);
    }

    /** 시드한 업무 행의 상태·갱신 시각 스냅샷(점검이 업무 데이터를 건드리지 않았는지 대조). */
    private String businessSnapshot() {
        return String.join("|", jdbc.queryForList(
                "SELECT CONCAT('p', id, status, updated_at) FROM payment WHERE order_id BETWEEN ? AND ? "
                        + "UNION ALL SELECT CONCAT('i', id, item_status, updated_at) FROM order_item WHERE order_id BETWEEN ? AND ? "
                        + "UNION ALL SELECT CONCAT('o', id, status, updated_at) FROM `order` WHERE id BETWEEN ? AND ? "
                        + "UNION ALL SELECT CONCAT('c', id, status, updated_at) FROM claim WHERE id BETWEEN ? AND ? "
                        + "UNION ALL SELECT CONCAT('r', id, status, updated_at) FROM refund WHERE id IN (?, ?) "
                        + "UNION ALL SELECT CONCAT('d', id, status, updated_at) FROM delivery WHERE id IN (?, ?) ORDER BY 1",
                String.class, U5_ORDER_ID, U13_ORDER_ID, U5_ORDER_ID, U13_ORDER_ID, U5_ORDER_ID, U13_ORDER_ID,
                U6_CLAIM_ID, U11_CONTROL_ACTIVE_CLAIM_ID, U6_CLAIM_ID, U7_CLAIM_ID, U12_DELIVERY_ID, U13_DELIVERY_ID));
    }
}

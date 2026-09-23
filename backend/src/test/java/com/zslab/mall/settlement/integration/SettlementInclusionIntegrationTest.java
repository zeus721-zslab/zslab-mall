package com.zslab.mall.settlement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.exception.SettlementNegativeNetException;
import com.zslab.mall.settlement.repository.SettlementRepository;
import com.zslab.mall.settlement.repository.SettlementStatusTotalProjection;
import com.zslab.mall.settlement.service.SettlementCreationService;
import com.zslab.mall.settlement.service.SettlementTransitionService;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정산 편입 조건 통합 테스트(Track 104-3b·결정 ⑥⑦⑧·실 MariaDB). 정산은 "기간 말까지 발생했고 아직 어느 정산에도 편입되지 않은 사실"을 모두
 * 가져온다 — 기간 하한이 없고, 편입 여부는 settlement_item (item_type, source_id) 전역 키로 판정한다. 열린(OPEN) 불일치가 있는 주문은 보류하고,
 * 확정됐지만 순지급액이 음수라 지급이 막힌 정산의 부족분은 다음 정산에 이월(CARRYOVER) 차감한다.
 *
 * <p><b>시드</b>: id 7341~7399 대역(셀러·주문·품목·클레임·환불)과 마감된 2025년 기간만 쓴다. 정산 생성은 전 셀러 대상이라, 이 테스트 중에
 * 만들어진 정산(다른 셀러 포함)은 시작 시점의 최대 정산 id보다 큰 행을 지워 공유 DB에 남기지 않는다. 클래스에 {@code @Transactional}을
 * 두지 않는다(정산 생성은 자체 REPEATABLE READ 트랜잭션). 모든 SQL은 ? positional 바인딩 + 정적 SQL이다(SQL injection 위험 없음).
 */
class SettlementInclusionIntegrationTest extends AbstractIntegrationTest {

    private static final long SELLER_A = 7341L;
    private static final long SELLER_B = 7342L;
    private static final long SELLER_C = 7343L;
    private static final long SELLER_D = 7344L;
    private static final long ORDER_A = 7351L;
    private static final long ORDER_B = 7352L;
    private static final long ORDER_C = 7353L;
    private static final long ITEM_A1 = 7361L;
    private static final long ITEM_A2 = 7362L;
    private static final long ITEM_B1 = 7363L;
    private static final long ITEM_B2 = 7364L;
    private static final long ITEM_C1 = 7365L;
    private static final long CLAIM_A1 = 7371L;
    private static final long CLAIM_B2 = 7372L;
    private static final long REFUND_A1 = 7381L;
    private static final long REFUND_B2 = 7382L;
    private static final long PAYMENT_A = 7391L;
    private static final long PAYMENT_A_AMOUNT = 25_000L;
    private static final long ID_RANGE_START = 7340L;
    private static final long ID_RANGE_END = 7399L;
    private static final long SALE_AMOUNT = 10_000L;
    private static final int YEAR = 2025;
    private static final LocalDateTime JAN_15 = LocalDateTime.of(2025, 1, 15, 12, 0);
    private static final LocalDateTime FEB_START = LocalDateTime.of(2025, 2, 1, 0, 0);
    private static final LocalDateTime FEB_END = LocalDateTime.of(2025, 2, 28, 23, 59, 59, 999_999_000);
    private static final LocalDateTime JAN_START = LocalDateTime.of(2025, 1, 1, 0, 0);
    private static final LocalDateTime JAN_END = LocalDateTime.of(2025, 1, 31, 23, 59, 59, 999_999_000);
    private static final LocalDateTime MAR_START = LocalDateTime.of(2025, 3, 1, 0, 0);
    private static final LocalDateTime APR_START = LocalDateTime.of(2025, 4, 1, 0, 0);
    private static final LocalDateTime APR_END = LocalDateTime.of(2025, 4, 30, 23, 59, 59, 999_999_000);
    private static final AuditContext ADMIN = AuditContext.of(7349L, "ADMIN");

    @Autowired
    private SettlementCreationService settlementCreationService;
    @Autowired
    private SettlementTransitionService settlementTransitionService;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private long settlementIdBefore;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanupSeeds();
        settlementIdBefore = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM settlement", Long.class);
    }

    @AfterEach
    void tearDown() {
        cleanupCreatedSettlements();
        cleanupSeeds();
    }

    @Test
    @DisplayName("I1 기간 하한 없는 편입: 1월 확정·미정산 매출 → 3월 정산에 편입(헤더 기간 3월·품목 발생 시각은 원래 1월·출처 = 품목 id)")
    void earlierUnsettledSale_isIncludedInLaterPeriod() {
        seedSeller(SELLER_A);
        seedOrder(ORDER_A);
        seedItem(ITEM_A1, ORDER_A, SELLER_A, "CONFIRMED", JAN_15, SALE_AMOUNT);

        settlementCreationService.createMonthlySettlements(YEAR, 3, ADMIN);

        assertThat(amounts(SELLER_A, MAR_START)).isEqualTo("gross=10000 fee=1000 refund=0 carryover=0 net=9000");
        Map<String, Object> item = jdbc.queryForMap("SELECT source_id, occurred_at FROM settlement_item WHERE item_type = 'SALE' "
                + "AND settlement_id = ?", settlementId(SELLER_A, MAR_START));
        assertThat(((Number) item.get("source_id")).longValue()).isEqualTo(ITEM_A1);
        assertThat(((java.sql.Timestamp) item.get("occurred_at")).toLocalDateTime()).isEqualTo(JAN_15);
    }

    @Test
    @DisplayName("I2 이미 편입된 건 제외: 2월 정산(지급 완료)에 들어간 매출은 3월에 다시 들어오지 않고, 그 품목의 3월 환불은 3월 차감으로 들어온다"
            + " · 결제액 = order_item.total_price(⑤ — 결제 금액·상태와 무관)")
    void alreadyIncludedSale_isExcluded_andRefundAfterPayoutIsDeductedNext() {
        seedSeller(SELLER_A);
        seedOrder(ORDER_A);
        // ⑤: 주문 결제는 품목 합(10,000 + 20,000)과 다른 금액이고 취소 상태다 — 정산은 결제 행을 읽지 않고 품목 total_price만 쓴다.
        seedPayment(PAYMENT_A, ORDER_A, PAYMENT_A_AMOUNT, "CANCELLED");
        seedItem(ITEM_A1, ORDER_A, SELLER_A, "CONFIRMED", LocalDateTime.of(2025, 2, 10, 12, 0), SALE_AMOUNT);
        settlementCreationService.createMonthlySettlements(YEAR, 2, ADMIN);
        assertThat(amounts(SELLER_A, FEB_START)).as("gross = order_item.total_price(결제 %d·CANCELLED 아님)", PAYMENT_A_AMOUNT)
                .isEqualTo("gross=10000 fee=1000 refund=0 carryover=0 net=9000");
        jdbc.update("UPDATE settlement SET status = 'PAID', paid_at = NOW(6) WHERE id = ?", settlementId(SELLER_A, FEB_START));

        seedItem(ITEM_A2, ORDER_A, SELLER_A, "CONFIRMED", LocalDateTime.of(2025, 3, 10, 12, 0), 20_000L);
        seedCompletedRefund(CLAIM_A1, REFUND_A1, ITEM_A1, 3_000L, LocalDateTime.of(2025, 3, 20, 12, 0));
        settlementCreationService.createMonthlySettlements(YEAR, 3, ADMIN);

        assertThat(amounts(SELLER_A, MAR_START)).isEqualTo("gross=20000 fee=2000 refund=3000 carryover=0 net=15000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_item WHERE item_type = 'SALE' AND source_id = ?",
                Long.class, ITEM_A1)).as("같은 매출 품목은 한 정산에만").isEqualTo(1L);
        assertThat(amounts(SELLER_A, FEB_START)).as("지급된 정산은 그대로").isEqualTo("gross=10000 fee=1000 refund=0 carryover=0 net=9000");
    }

    @Test
    @DisplayName("I3 보류·해제: OPEN 불일치 주문의 매출·환불은 2월 정산에서 빠지고, 해제(RESOLVED) 후 3월 정산에 함께 편입된다")
    void openIssueOrder_isHeld_andIncludedAfterResolution() {
        seedSeller(SELLER_B);
        seedOrder(ORDER_B);
        seedItem(ITEM_B1, ORDER_B, SELLER_B, "CONFIRMED", LocalDateTime.of(2025, 2, 10, 12, 0), SALE_AMOUNT);
        seedItem(ITEM_B2, ORDER_B, SELLER_B, "CONFIRMED", LocalDateTime.of(2025, 2, 11, 12, 0), SALE_AMOUNT);
        seedCompletedRefund(CLAIM_B2, REFUND_B2, ITEM_B2, 4_000L, LocalDateTime.of(2025, 2, 20, 12, 0));
        jdbc.update("INSERT INTO reconciliation_issue (issue_type, dedupe_key, order_id, status, detected_at, created_at) "
                + "VALUES ('PAYMENT_CANCELLED_WITHOUT_REFUND', ?, ?, 'OPEN', NOW(6), NOW(6))", "payment:7352", ORDER_B);

        settlementCreationService.createMonthlySettlements(YEAR, 2, ADMIN);
        assertThat(settlementCount(SELLER_B)).as("보류 주문뿐인 셀러는 정산 없음").isZero();

        jdbc.update("UPDATE reconciliation_issue SET status = 'RESOLVED', resolved_at = NOW(6), resolution_memo = '테스트 해소' "
                + "WHERE order_id = ?", ORDER_B);
        settlementCreationService.createMonthlySettlements(YEAR, 3, ADMIN);

        assertThat(amounts(SELLER_B, MAR_START)).isEqualTo("gross=20000 fee=2000 refund=4000 carryover=0 net=14000");
        assertThat(itemTypes(settlementId(SELLER_B, MAR_START))).containsExactlyInAnyOrder("SALE", "SALE", "REFUND");
    }

    @Test
    @DisplayName("I4 음수 정산 이월: 확정된 음수 정산만 다음 정산에 CARRYOVER 차감(PENDING 음수 제외)·원 정산은 CONFIRMED 유지·지급 차단·"
            + "이월만 있는 셀러도 생성·재생성 후에도 1회·다음 달 재편입 없음")
    void confirmedNegativeSettlement_isCarriedOverOnce() {
        seedSeller(SELLER_C);
        seedSeller(SELLER_D);
        seedSettlement(SELLER_C, JAN_START, JAN_END, "PENDING", -1_000L);
        seedSettlement(SELLER_C, FEB_START, FEB_END, "CONFIRMED", -5_000L);
        seedSettlement(SELLER_D, FEB_START, FEB_END, "CONFIRMED", -2_000L);
        seedOrder(ORDER_C);
        seedItem(ITEM_C1, ORDER_C, SELLER_C, "CONFIRMED", LocalDateTime.of(2025, 3, 5, 12, 0), SALE_AMOUNT);

        settlementCreationService.createMonthlySettlements(YEAR, 3, ADMIN);

        assertThat(amounts(SELLER_C, MAR_START)).isEqualTo("gross=10000 fee=1000 refund=0 carryover=5000 net=4000");
        Long negativeFeb = settlementId(SELLER_C, FEB_START);
        Map<String, Object> carryover = jdbc.queryForMap("SELECT source_id, amount, fee_amount, commission_rate, occurred_at, "
                + "order_item_id FROM settlement_item WHERE item_type = 'CARRYOVER' AND settlement_id = ?", settlementId(SELLER_C, MAR_START));
        assertThat(((Number) carryover.get("source_id")).longValue()).as("출처 = 원 음수 정산 id").isEqualTo(negativeFeb);
        assertThat(((Number) carryover.get("amount")).longValue()).isEqualTo(5_000L);
        assertThat(((Number) carryover.get("fee_amount")).longValue()).isZero();
        assertThat(((Number) carryover.get("commission_rate")).intValue()).isZero();
        assertThat(((java.sql.Timestamp) carryover.get("occurred_at")).toLocalDateTime()).isEqualTo(FEB_END);
        assertThat(carryover.get("order_item_id")).isNull();
        assertThat(amounts(SELLER_D, MAR_START)).as("이월만 있는 셀러").isEqualTo("gross=0 fee=0 refund=0 carryover=2000 net=-2000");

        // ⑧ 원 정산은 되돌리지 않는다(상쇄): 이월 뒤에도 CONFIRMED·net −5000 그대로이고 지급은 계속 막힌다(422 SETTLEMENT_NET_NEGATIVE).
        assertThat(statusOf(negativeFeb)).as("이월 직후 원 정산 상태 그대로").isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> settlementTransitionService.pay(negativeFeb, ADMIN))
                .as("이월된 원 음수 정산도 지급 차단").isInstanceOf(SettlementNegativeNetException.class);
        assertThat(statusOf(negativeFeb)).as("지급 시도 실패 후에도 CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(amounts(SELLER_C, FEB_START)).isEqualTo("gross=0 fee=0 refund=5000 carryover=0 net=-5000");

        settlementCreationService.regenerate(settlementId(SELLER_C, MAR_START), "이월 재생성", ADMIN);
        assertThat(amounts(SELLER_C, MAR_START)).as("재생성은 자기 이월 행을 지운 뒤 다시 편입")
                .isEqualTo("gross=10000 fee=1000 refund=0 carryover=5000 net=4000");

        settlementCreationService.createMonthlySettlements(YEAR, 4, ADMIN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_item WHERE item_type = 'CARRYOVER' AND source_id = ?",
                Long.class, negativeFeb)).as("같은 음수 정산은 한 번만 이월").isEqualTo(1L);
        assertThat(settlementCount(SELLER_C)).as("4월에는 C 소스 없음(1월 PENDING 음수는 이월 대상 아님)").isEqualTo(3);
    }

    @Test
    @DisplayName("I5 합계 이중 반영 없음: 이월 전 음수 정산은 합계에 반영, 다음 정산에 이월된 뒤에는 원 정산 net을 월·셀러 합계에서 빼 부족분이 한 번만 반영(건수 불변)"
            + " · 대조군: 이월되지 않은 CONFIRMED 음수 정산(4월)은 계속 포함 — 음수라서가 아니라 이월돼서 제외")
    void carriedOverNegativeSettlement_isCountedOnceInTotals() {
        seedSeller(SELLER_C);
        seedSettlement(SELLER_C, FEB_START, FEB_END, "CONFIRMED", -5_000L);
        // 대조군: 3월 생성의 이월 대상은 기간 말 ≤ 3월 말뿐이라 4월 음수 정산은 이월되지 않는다.
        SettlementStatusTotalProjection aprBase = confirmedRowOrNull(settlementRepository.sumByStatusForPeriod(APR_START, APR_END));
        seedSettlement(SELLER_C, APR_START, APR_END, "CONFIRMED", -3_000L);
        assertThat(totalsByStatus(settlementRepository.sumByStatusForSeller(SELLER_C)))
                .as("아직 이월되지 않은 음수 정산은 합계에 그대로").containsExactly(entry(SettlementStatus.CONFIRMED, "2:8000:0:-8000"));
        SettlementStatusTotalProjection febBefore = confirmedRow(settlementRepository.sumByStatusForPeriod(FEB_START, FEB_END));

        seedOrder(ORDER_C);
        seedItem(ITEM_C1, ORDER_C, SELLER_C, "CONFIRMED", LocalDateTime.of(2025, 3, 5, 12, 0), SALE_AMOUNT);
        settlementCreationService.createMonthlySettlements(YEAR, 3, ADMIN);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_item WHERE item_type = 'CARRYOVER' AND source_id = ?",
                Long.class, settlementId(SELLER_C, APR_START))).as("4월 음수 정산은 이월되지 않음(대조군 전제)").isZero();
        assertThat(totalsByStatus(settlementRepository.sumByStatusForSeller(SELLER_C)))
                .as("원 정산 행은 남고(건수·환불 그대로) 이월된 2월 net만 빠지고 이월 안 된 4월 −3000은 남는다")
                .containsOnly(entry(SettlementStatus.CONFIRMED, "2:8000:0:-3000"), entry(SettlementStatus.PENDING, "1:0:5000:4000"));
        // 월 합계는 셀러로 거를 수 없어(공유 DB의 같은 달 다른 정산) 이월 전후 차이로 본다.
        SettlementStatusTotalProjection febAfter = confirmedRow(settlementRepository.sumByStatusForPeriod(FEB_START, FEB_END));
        assertThat(febAfter.getSettlementCount()).as("2월 월 합계 건수 불변").isEqualTo(febBefore.getSettlementCount());
        assertThat(febAfter.getRefundAmount()).isEqualTo(febBefore.getRefundAmount());
        assertThat(febAfter.getNetAmount() - febBefore.getNetAmount()).as("2월 월 합계 net에서 이월된 −5000만 빠짐").isEqualTo(5_000L);
        SettlementStatusTotalProjection aprAfter = confirmedRow(settlementRepository.sumByStatusForPeriod(APR_START, APR_END));
        assertThat(aprAfter.getSettlementCount() - countOf(aprBase)).as("4월 월 합계에 대조군 1건").isEqualTo(1L);
        assertThat(aprAfter.getNetAmount() - netOf(aprBase)).as("4월 월 합계 net에 이월 안 된 −3000이 그대로 포함").isEqualTo(-3_000L);
        assertThat(jdbc.queryForObject("SELECT SUM(net_amount) FROM settlement WHERE seller_id = ?", Long.class, SELLER_C))
                .as("행 net 합(-5000 − 3000 + 4000)은 2월 부족분을 두 번 뺀 값 — 합계 쿼리는 이 값을 쓰지 않는다").isEqualTo(-4_000L);
    }

    // ---------- seed·helpers ----------

    /** 상태별 "건수:환불 합:이월 합:net 합". */
    private static Map<SettlementStatus, String> totalsByStatus(List<SettlementStatusTotalProjection> rows) {
        return rows.stream().collect(Collectors.toMap(SettlementStatusTotalProjection::getStatus,
                row -> row.getSettlementCount() + ":" + row.getRefundAmount() + ":" + row.getCarryoverAmount() + ":" + row.getNetAmount()));
    }

    private static SettlementStatusTotalProjection confirmedRow(List<SettlementStatusTotalProjection> rows) {
        return rows.stream().filter(row -> row.getStatus() == SettlementStatus.CONFIRMED).findFirst().orElseThrow();
    }

    /** 공유 DB에 그 달 CONFIRMED 정산이 없으면 null(기준값 0으로 본다). */
    private static SettlementStatusTotalProjection confirmedRowOrNull(List<SettlementStatusTotalProjection> rows) {
        return rows.stream().filter(row -> row.getStatus() == SettlementStatus.CONFIRMED).findFirst().orElse(null);
    }

    private static long countOf(SettlementStatusTotalProjection row) {
        return row == null ? 0L : row.getSettlementCount();
    }

    private static long netOf(SettlementStatusTotalProjection row) {
        return row == null ? 0L : row.getNetAmount();
    }

    private void seedSeller(long sellerId) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, '편입셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", sellerId, pid("slr_", "INC" + sellerId));
    }

    private void seedOrder(long orderId) {
        withoutForeignKeys(() -> jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                + "discount_amount, shipping_fee, created_at, updated_at) VALUES (?, ?, 1, ?, 'CONFIRMED', 0, 0, 0, NOW(6), NOW(6))",
                orderId, pid("ord_", "INC" + orderId), "ORDINC" + orderId));
    }

    private void seedItem(long itemId, long orderId, long sellerId, String itemStatus, LocalDateTime confirmedAt, long amount) {
        withoutForeignKeys(() -> jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                + "quantity, unit_price, total_price, commission_rate, item_status, confirmed_at, created_at, updated_at, product_name) "
                + "VALUES (?, ?, ?, 1, 1, ?, 1, ?, ?, 1000, ?, ?, NOW(6), NOW(6), '편입 상품')",
                itemId, pid("oit_", "INC" + itemId), orderId, sellerId, amount, amount, itemStatus, confirmedAt));
    }

    private void seedPayment(long paymentId, long orderId, long amount, String status) {
        withoutForeignKeys(() -> jdbc.update("INSERT INTO payment (id, public_id, order_id, method, amount, status, payment_attempt_key, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'CARD', ?, ?, ?, NOW(6), NOW(6))",
                paymentId, pid("pay_", "INC" + paymentId), orderId, amount, status, pid("pat_", "INC" + paymentId)));
    }

    private void seedCompletedRefund(long claimId, long refundId, long itemId, long amount, LocalDateTime refundedAt) {
        withoutForeignKeys(() -> {
            jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                    + "version, created_at, updated_at) VALUES (?, ?, ?, 'EXCHANGE', 'PRODUCT_DEFECT', 'APPROVED', 'DELIVERED', 0, "
                    + "NOW(6), NOW(6))", claimId, pid("clm_", "INC" + claimId), itemId);
            jdbc.update("INSERT INTO refund (id, public_id, claim_id, payment_id, amount, status, pg_refund_id, refunded_at, "
                    + "created_at, updated_at) VALUES (?, ?, ?, 1, ?, 'COMPLETED', ?, ?, NOW(6), NOW(6))",
                    refundId, pid("rfn_", "INC" + refundId), claimId, amount, "inc_rfn_" + refundId, refundedAt);
        });
    }

    /** 음수 정산 헤더만 시드한다(gross·fee 0·refund = −net). id는 AUTO_INCREMENT — 공유 DB의 기존 id와 겹치지 않게 지정하지 않는다. */
    private void seedSettlement(long sellerId, LocalDateTime periodStart, LocalDateTime periodEnd, String status, long netAmount) {
        jdbc.update("INSERT INTO settlement (seller_id, period_start, period_end, gross_amount, fee_amount, refund_amount, net_amount, "
                + "status, created_at, updated_at) VALUES (?, ?, ?, 0, 0, ?, ?, ?, NOW(6), NOW(6))",
                sellerId, periodStart, periodEnd, -netAmount, netAmount, status);
    }

    private Long settlementId(long sellerId, LocalDateTime periodStart) {
        return jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ? AND period_start = ?", Long.class, sellerId, periodStart);
    }

    private String amounts(long sellerId, LocalDateTime periodStart) {
        return jdbc.queryForObject("SELECT CONCAT('gross=', gross_amount, ' fee=', fee_amount, ' refund=', refund_amount, "
                + "' carryover=', carryover_amount, ' net=', net_amount) FROM settlement WHERE seller_id = ? AND period_start = ?",
                String.class, sellerId, periodStart);
    }

    private String statusOf(Long settlementId) {
        return jdbc.queryForObject("SELECT status FROM settlement WHERE id = ?", String.class, settlementId);
    }

    private int settlementCount(long sellerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE seller_id = ?", Integer.class, sellerId);
    }

    private List<String> itemTypes(Long settlementId) {
        return jdbc.queryForList("SELECT item_type FROM settlement_item WHERE settlement_id = ?", String.class, settlementId);
    }

    /** 이 테스트 중에 만들어진 정산(전 셀러 대상 생성이 다른 셀러의 잔여 사실을 편입한 것 포함)과 시드 셀러의 정산을 지운다. */
    private void cleanupCreatedSettlements() {
        withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'SETTLEMENT' AND (target_id > ? OR target_id IN "
                    + "(SELECT id FROM settlement WHERE seller_id BETWEEN ? AND ?))", settlementIdBefore, ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM settlement_item WHERE settlement_id > ? OR settlement_id IN "
                    + "(SELECT id FROM settlement WHERE seller_id BETWEEN ? AND ?)", settlementIdBefore, ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM settlement WHERE id > ? OR seller_id BETWEEN ? AND ?", settlementIdBefore, ID_RANGE_START,
                    ID_RANGE_END);
        });
    }

    private void cleanupSeeds() {
        withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM settlement_item WHERE settlement_id IN (SELECT id FROM settlement WHERE seller_id BETWEEN ? AND ?)",
                    ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM settlement WHERE seller_id BETWEEN ? AND ?", ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM reconciliation_issue WHERE order_id BETWEEN ? AND ?", ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM refund WHERE id BETWEEN ? AND ?", ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM payment WHERE id BETWEEN ? AND ?", ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_RANGE_START, ID_RANGE_END);
            jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", ID_RANGE_START, ID_RANGE_END);
        });
    }

    private void withoutForeignKeys(Runnable work) {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                work.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

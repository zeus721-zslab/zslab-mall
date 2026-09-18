package com.zslab.mall.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.SellerGrossProjection;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.refund.repository.SellerRefundProjection;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.entity.SettlementItem;
import com.zslab.mall.settlement.enums.SettlementItemType;
import com.zslab.mall.settlement.exception.SettlementInvalidStateException;
import com.zslab.mall.settlement.exception.SettlementNotFoundException;
import com.zslab.mall.settlement.exception.SettlementPeriodInvalidException;
import com.zslab.mall.settlement.repository.SettlementItemRepository;
import com.zslab.mall.settlement.repository.SettlementRepository;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link SettlementCreationService} @DataJpaTest(Track 48 P3 → Track 85 품목 스냅샷). 실 소스 쿼리를 MariaDB에 대해 구동해
 * seller별 병합·품목별 fee 버림 합·net 음수·계좌 없이 생성·지급예정일·D-168 가드·재실행 멱등·재생성·감사 호출을 검증한다.
 * 서비스는 {@link Import}로 컨텍스트에 올리고 {@link AuditRecorder}는 MockitoBean으로 대체해 호출만 검증한다(GradeServiceTest 패턴).
 *
 * <p>시드 시각은 {@code LocalDateTime} 바인딩 파라미터로 넣는다(P2 트랩 대응). order_item은 {@code JOIN oi.order}로 주문 public_id를
 * 읽으므로 order 행을 함께 시드한다. FOREIGN_KEY_CHECKS=0 시드 후 @AfterEach로 복구(커넥션 풀 누수 방지).
 */
@Import(SettlementCreationService.class)
class SettlementCreationServiceTest extends Batch1DataJpaTestBase {

    private static final long SELLER_MIXED = 9401L;    // 매출+환불
    private static final long SELLER_SALES_ONLY = 9402L; // 매출만(율 500)
    private static final long SELLER_REFUND_ONLY = 9403L; // 환불만(net 음수)
    private static final long SELLER_NO_ACCOUNT = 9404L;  // 매출 있으나 정산계좌 없음 → Track 85부터 생성됨
    private static final long ORDER_ID = 9401L;
    private static final String ORDER_PUBLIC_ID = "ord_STL85ORDER000000000000000";
    private static final LocalDateTime IN_PERIOD = LocalDateTime.of(2026, 6, 15, 12, 0, 0);
    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 6, 1, 0, 0, 0);
    private static final LocalDateTime PERIOD_END = LocalDateTime.of(2026, 6, 30, 23, 59, 59, 999_999_000);
    private static final AuditContext ADMIN = AuditContext.of(9400L, "ADMIN");

    @Autowired
    private SettlementCreationService settlementCreationService;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private SettlementItemRepository settlementItemRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private RefundRepository refundRepository;
    @MockitoBean
    private AuditRecorder auditRecorder;

    private int seq = 0;

    @AfterEach
    void restoreForeignKeyChecks() {
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    private void disableFkChecks() {
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
    }

    private void insertSeller(long id) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
            + "VALUES (:id, :pid, '정산셀러', '대표', 'ACTIVE', NULL, NOW(6), NOW(6))");
        query.setParameter("id", id);
        query.setParameter("pid", String.format("slr_%026d", id));
        query.executeUpdate();
    }

    private void insertOrder() {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
            + "created_at, updated_at) VALUES (:id, :pid, 1, :orderNo, 'CONFIRMED', 0, 0, 0, NOW(6), NOW(6))");
        query.setParameter("id", ORDER_ID);
        query.setParameter("pid", ORDER_PUBLIC_ID);
        query.setParameter("orderNo", "STL85-" + ORDER_ID);
        query.executeUpdate();
    }

    private void insertPrimaryBankAccount(long sellerId) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO seller_bank_account "
            + "(seller_id, bank_code, account_number, account_holder, is_primary, status, created_at, updated_at) "
            + "VALUES (:sellerId, '004', '123', '대표', 1, 'VERIFIED', NOW(6), NOW(6))");
        query.setParameter("sellerId", sellerId);
        query.executeUpdate();
    }

    /** 구매확정 품목(율 스냅샷 포함·옵션 라벨 스냅샷 고정). */
    private long insertConfirmedOrderItem(long sellerId, long totalPrice, int commissionRate, LocalDateTime confirmedAt) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO order_item "
            + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, commission_rate, "
            + "item_status, confirmed_at, created_at, updated_at, product_name, option_label) "
            + "VALUES (:pid, :orderId, 1, 1, :seller, 1, :total, :total, :rate, 'CONFIRMED', :confirmedAt, NOW(6), NOW(6), "
            + "'테스트 상품', '색상: 블랙')");
        query.setParameter("pid", String.format("oit_%026d", ++seq));
        query.setParameter("orderId", ORDER_ID);
        query.setParameter("seller", sellerId);
        query.setParameter("total", totalPrice);
        query.setParameter("rate", commissionRate);
        query.setParameter("confirmedAt", confirmedAt);
        query.executeUpdate();
        return lastInsertId();
    }

    /**
     * 환불 귀속용 품목. gross에 집계된 적이 있는(confirmed_at 설정·전 기간 확정) 품목만 환불이 차감되므로(Track 79 D-168·B)
     * confirmed_at을 정산 기간 이전으로 둔다(이번 기간 gross에는 미포함·환불만 차감되는 케이스). confirmedAt=null이면 미확정 품목.
     */
    private long insertOrderItemForClaim(long sellerId, LocalDateTime confirmedAt) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO order_item "
            + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, commission_rate, "
            + "item_status, confirmed_at, created_at, updated_at, product_name) "
            + "VALUES (:pid, :orderId, 1, 1, :seller, 1, 1000, 1000, 1000, 'RETURNED', :confirmedAt, NOW(6), NOW(6), '테스트 상품')");
        query.setParameter("pid", String.format("oit_%026d", ++seq));
        query.setParameter("orderId", ORDER_ID);
        query.setParameter("seller", sellerId);
        query.setParameter("confirmedAt", confirmedAt);
        query.executeUpdate();
        return lastInsertId();
    }

    private long insertClaim(long orderItemId) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO claim "
            + "(public_id, order_item_id, type, reason_code, status, previous_order_item_status, created_at, updated_at) "
            + "VALUES (:pid, :orderItemId, 'RETURN', 'DEFECT', 'COMPLETED', 'DELIVERED', NOW(6), NOW(6))");
        query.setParameter("pid", String.format("clm_%026d", ++seq));
        query.setParameter("orderItemId", orderItemId);
        query.executeUpdate();
        return lastInsertId();
    }

    private long insertCompletedRefund(long claimId, long amount, LocalDateTime refundedAt) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO refund "
            + "(public_id, claim_id, payment_id, amount, status, refunded_at, created_at, updated_at) "
            + "VALUES (:pid, :claimId, 1, :amount, 'COMPLETED', :refundedAt, NOW(6), NOW(6))");
        query.setParameter("pid", String.format("rfn_%026d", ++seq));
        query.setParameter("claimId", claimId);
        query.setParameter("amount", amount);
        query.setParameter("refundedAt", refundedAt);
        query.executeUpdate();
        return lastInsertId();
    }

    /** seller에 대해 (claim 경유) COMPLETED 환불 1건을 시드한다(품목은 전 기간 확정). */
    private long seedRefundForSeller(long sellerId, long amount, LocalDateTime refundedAt) {
        long orderItemId = insertOrderItemForClaim(sellerId, LocalDateTime.of(2026, 5, 15, 0, 0));
        long claimId = insertClaim(orderItemId);
        return insertCompletedRefund(claimId, amount, refundedAt);
    }

    private long lastInsertId() {
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private Map<Long, Settlement> createdBySeller(SettlementBatchResult result) {
        return result.created().stream()
            .collect(Collectors.toMap(Settlement::getSellerId, Function.identity()));
    }

    private List<SettlementItem> items(Long settlementId, SettlementItemType type) {
        return settlementItemRepository.findBySettlementIdAndItemType(settlementId, type, PageRequest.of(0, 50)).getContent();
    }

    @Test
    @DisplayName("createMonthlySettlements: 매출/환불/양쪽 seller 병합·품목별 fee·net 음수·계좌 없이 생성·품목 스냅샷·지급예정일·D-168 가드·CREATE 감사")
    void createMonthlySettlements_mergesAndComputes() {
        insertOrder();
        insertSeller(SELLER_MIXED);
        insertSeller(SELLER_SALES_ONLY);
        insertSeller(SELLER_REFUND_ONLY);
        insertSeller(SELLER_NO_ACCOUNT);
        insertPrimaryBankAccount(SELLER_MIXED);
        // SELLER_NO_ACCOUNT: 정산계좌 미시드 → Track 85부터 계좌 없이도 생성(지급 시점에만 필수)

        long mixedItemId = insertConfirmedOrderItem(SELLER_MIXED, 10_333L, 1000, IN_PERIOD); // fee 10333*1000/10000=1033
        insertConfirmedOrderItem(SELLER_SALES_ONLY, 20_000L, 500, IN_PERIOD);                // fee 20000*500/10000=1000
        insertConfirmedOrderItem(SELLER_NO_ACCOUNT, 50_000L, 1000, IN_PERIOD);
        long mixedRefundId = seedRefundForSeller(SELLER_MIXED, 333L, IN_PERIOD);            // net=10333-1033-333=8967
        seedRefundForSeller(SELLER_REFUND_ONLY, 5_000L, IN_PERIOD);                          // gross 0·net=-5000
        // D-168 가드: 확정 이력 없는(confirmed_at NULL) 품목의 환불은 차감하지 않는다
        insertCompletedRefund(insertClaim(insertOrderItemForClaim(SELLER_MIXED, null)), 9_999L, IN_PERIOD);
        entityManager.flush();
        entityManager.clear();

        SettlementBatchResult result = settlementCreationService.createMonthlySettlements(2026, 6, ADMIN);

        assertThat(result.periodStart()).isEqualTo(PERIOD_START);
        assertThat(result.periodEnd()).isEqualTo(PERIOD_END);
        Map<Long, Settlement> bySeller = createdBySeller(result);
        assertThat(bySeller).containsOnlyKeys(SELLER_MIXED, SELLER_SALES_ONLY, SELLER_REFUND_ONLY, SELLER_NO_ACCOUNT);

        Settlement mixed = bySeller.get(SELLER_MIXED);
        assertThat(mixed.getGrossAmount()).isEqualTo(10_333L);
        assertThat(mixed.getFeeAmount()).isEqualTo(1_033L);       // 버림 검증
        assertThat(mixed.getRefundAmount()).isEqualTo(333L);       // 9999(미확정 품목 환불)는 제외
        assertThat(mixed.getNetAmount()).isEqualTo(8_967L);
        assertThat(mixed.getCommissionRate()).isNull();            // 헤더 율은 더 이상 채우지 않는다
        assertThat(mixed.getBankAccountId()).isNull();             // 계좌는 지급 시점 스냅샷
        assertThat(mixed.getScheduledPayDate()).isEqualTo(LocalDate.of(2026, 7, 20)); // 말일 6/30 + 20일

        List<SettlementItem> mixedSales = items(mixed.getId(), SettlementItemType.SALE);
        assertThat(mixedSales).hasSize(1);
        SettlementItem sale = mixedSales.get(0);
        assertThat(sale.getOrderItemId()).isEqualTo(mixedItemId);
        assertThat(sale.getRefundId()).isNull();
        assertThat(sale.getOrderPublicId()).isEqualTo(ORDER_PUBLIC_ID);
        assertThat(sale.getProductName()).isEqualTo("테스트 상품");
        assertThat(sale.getOptionLabel()).isEqualTo("색상: 블랙");
        assertThat(sale.getQuantity()).isEqualTo(1);
        assertThat(sale.getAmount()).isEqualTo(10_333L);
        assertThat(sale.getCommissionRate()).isEqualTo(1000);
        assertThat(sale.getFeeAmount()).isEqualTo(1_033L);
        assertThat(sale.getOccurredAt()).isEqualTo(IN_PERIOD);
        List<SettlementItem> mixedRefunds = items(mixed.getId(), SettlementItemType.REFUND);
        assertThat(mixedRefunds).hasSize(1);
        assertThat(mixedRefunds.get(0).getRefundId()).isEqualTo(mixedRefundId);
        assertThat(mixedRefunds.get(0).getAmount()).isEqualTo(333L);
        assertThat(mixedRefunds.get(0).getFeeAmount()).isZero();
        assertThat(mixedRefunds.get(0).getOccurredAt()).isEqualTo(IN_PERIOD);

        Settlement salesOnly = bySeller.get(SELLER_SALES_ONLY);
        assertThat(salesOnly.getGrossAmount()).isEqualTo(20_000L);
        assertThat(salesOnly.getFeeAmount()).isEqualTo(1_000L);
        assertThat(salesOnly.getRefundAmount()).isEqualTo(0L);
        assertThat(salesOnly.getNetAmount()).isEqualTo(19_000L);
        assertThat(items(salesOnly.getId(), SettlementItemType.SALE).get(0).getCommissionRate()).isEqualTo(500);

        Settlement refundOnly = bySeller.get(SELLER_REFUND_ONLY);
        assertThat(refundOnly.getGrossAmount()).isEqualTo(0L);
        assertThat(refundOnly.getFeeAmount()).isEqualTo(0L);
        assertThat(refundOnly.getRefundAmount()).isEqualTo(5_000L);
        assertThat(refundOnly.getNetAmount()).isEqualTo(-5_000L);  // net 음수 허용(지급만 차단)

        Settlement noAccount = bySeller.get(SELLER_NO_ACCOUNT);
        assertThat(noAccount.getGrossAmount()).isEqualTo(50_000L);
        assertThat(noAccount.getBankAccountId()).isNull();

        // 품목에서 합산한 gross·refund는 기존 seller 집계 쿼리(Track 48 P2)와 같은 값이다
        Map<Long, Long> grossByAggregate = orderItemRepository
            .aggregateGrossBySeller(OrderItemStatus.CONFIRMED, PERIOD_START, PERIOD_END).stream()
            .collect(Collectors.toMap(SellerGrossProjection::getSellerId, SellerGrossProjection::getGrossAmount));
        Map<Long, Long> refundByAggregate = refundRepository
            .aggregateRefundBySeller(RefundStatus.COMPLETED, PERIOD_START, PERIOD_END).stream()
            .collect(Collectors.toMap(SellerRefundProjection::getSellerId, SellerRefundProjection::getRefundAmount));
        for (Settlement settlement : bySeller.values()) {
            assertThat(settlement.getGrossAmount()).isEqualTo(grossByAggregate.getOrDefault(settlement.getSellerId(), 0L));
            assertThat(settlement.getRefundAmount()).isEqualTo(refundByAggregate.getOrDefault(settlement.getSellerId(), 0L));
        }

        // 생성 감사: seller별 CREATE SETTLEMENT 1건씩(4건)
        verify(auditRecorder, times(4)).record(eq(ADMIN), eq(AuditLogAction.CREATE), eq(PolymorphicTargetType.SETTLEMENT),
            any(), anyMap(), anyMap());
    }

    @Test
    @DisplayName("createMonthlySettlements: fee = 품목별 버림 합(합계 버림과 다른 경계)·품목 율이 다르면 각자 적용")
    void createMonthlySettlements_feeIsSumOfPerItemFloor() {
        insertOrder();
        insertSeller(SELLER_MIXED);
        // 10005×1000/10000 = 1000.5 → 1000 씩 2건 = 2000. 합계 20010×1000/10000 = 2001(합계 버림이면 2001).
        insertConfirmedOrderItem(SELLER_MIXED, 10_005L, 1000, IN_PERIOD);
        insertConfirmedOrderItem(SELLER_MIXED, 10_005L, 1000, IN_PERIOD);
        // 주문 시점 율이 다른 품목(사후 율 변경 무영향 시나리오): 10000×500/10000 = 500
        insertConfirmedOrderItem(SELLER_MIXED, 10_000L, 500, IN_PERIOD);
        entityManager.flush();
        entityManager.clear();

        Settlement settlement = createdBySeller(settlementCreationService.createMonthlySettlements(2026, 6, ADMIN))
            .get(SELLER_MIXED);

        assertThat(settlement.getGrossAmount()).isEqualTo(30_010L);
        assertThat(settlement.getFeeAmount()).isEqualTo(2_500L);
        assertThat(items(settlement.getId(), SettlementItemType.SALE))
            .extracting(SettlementItem::getFeeAmount).containsExactlyInAnyOrder(1_000L, 1_000L, 500L);
    }

    @Test
    @DisplayName("createMonthlySettlements: month 범위 위반(0·13) → SettlementPeriodInvalidException")
    void createMonthlySettlements_invalidMonth_throws() {
        assertThatThrownBy(() -> settlementCreationService.createMonthlySettlements(2026, 0, ADMIN))
            .isInstanceOf(SettlementPeriodInvalidException.class);
        assertThatThrownBy(() -> settlementCreationService.createMonthlySettlements(2026, 13, ADMIN))
            .isInstanceOf(SettlementPeriodInvalidException.class);
    }

    @Test
    @DisplayName("createMonthlySettlements: 진행 중 월(오늘 포함)·다음 달 → SettlementPeriodInvalidException(마감 전 생성 차단·D-168 보충)")
    void createMonthlySettlements_periodNotClosed_throws() {
        YearMonth current = YearMonth.now();
        assertThatThrownBy(() -> settlementCreationService.createMonthlySettlements(
                current.getYear(), current.getMonthValue(), ADMIN))
            .isInstanceOf(SettlementPeriodInvalidException.class);
        YearMonth next = current.plusMonths(1);
        assertThatThrownBy(() -> settlementCreationService.createMonthlySettlements(
                next.getYear(), next.getMonthValue(), ADMIN))
            .isInstanceOf(SettlementPeriodInvalidException.class);
    }

    @Test
    @DisplayName("createMonthlySettlements: 재실행 시 기존 seller skip(중복 미생성·품목도 미중복·멱등)")
    void createMonthlySettlements_rerunSkips() {
        insertOrder();
        insertSeller(SELLER_MIXED);
        insertConfirmedOrderItem(SELLER_MIXED, 10_000L, 1000, IN_PERIOD);
        entityManager.flush();
        entityManager.clear();

        SettlementBatchResult first = settlementCreationService.createMonthlySettlements(2026, 6, ADMIN);
        assertThat(first.created()).hasSize(1);

        SettlementBatchResult second = settlementCreationService.createMonthlySettlements(2026, 6, ADMIN);
        assertThat(second.created()).isEmpty();          // 전부 skip
        assertThat(settlementRepository.count()).isEqualTo(1L);  // 중복 미생성
        assertThat(settlementItemRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("regenerate: PENDING → 품목·헤더 삭제 후 같은 seller·기간 재집계(새 id·변경 반영)·DELETE(reason)+CREATE 감사")
    void regenerate_pendingRecreates() {
        insertOrder();
        insertSeller(SELLER_MIXED);
        insertConfirmedOrderItem(SELLER_MIXED, 10_000L, 1000, IN_PERIOD);
        entityManager.flush();
        entityManager.clear();
        Settlement original = createdBySeller(settlementCreationService.createMonthlySettlements(2026, 6, ADMIN))
            .get(SELLER_MIXED);
        // 검수 후 누락 품목이 추가된 상황
        insertConfirmedOrderItem(SELLER_MIXED, 5_000L, 1000, IN_PERIOD);
        entityManager.flush();
        entityManager.clear();

        SettlementRegenerateResult result = settlementCreationService.regenerate(original.getId(), "품목 누락 정정", ADMIN);

        assertThat(result.deletedSettlementId()).isEqualTo(original.getId());
        assertThat(result.regenerated()).isNotNull();
        assertThat(result.regenerated().getId()).isNotEqualTo(original.getId());
        assertThat(result.regenerated().getGrossAmount()).isEqualTo(15_000L);
        assertThat(result.regenerated().getFeeAmount()).isEqualTo(1_500L);
        assertThat(settlementRepository.findById(original.getId())).isEmpty();
        assertThat(settlementRepository.count()).isEqualTo(1L);
        assertThat(settlementItemRepository.findBySettlementId(result.regenerated().getId(), PageRequest.of(0, 10))
            .getTotalElements()).isEqualTo(2L);
        assertThat(settlementItemRepository.count()).isEqualTo(2L);
        verify(auditRecorder).record(eq(ADMIN), eq(AuditLogAction.DELETE), eq(PolymorphicTargetType.SETTLEMENT),
            eq(original.getId()), org.mockito.ArgumentMatchers.argThat(before -> "품목 누락 정정".equals(before.get("reason"))),
            anyMap());
        verify(auditRecorder, times(2)).record(eq(ADMIN), eq(AuditLogAction.CREATE), eq(PolymorphicTargetType.SETTLEMENT),
            any(), anyMap(), anyMap());
    }

    @Test
    @DisplayName("regenerate: 재집계 대상이 없어졌으면 삭제만(regenerated=null)·CONFIRMED는 422·미존재는 404")
    void regenerate_deletedOnlyAndGuards() {
        insertOrder();
        insertSeller(SELLER_MIXED);
        long itemId = insertConfirmedOrderItem(SELLER_MIXED, 10_000L, 1000, IN_PERIOD);
        entityManager.flush();
        entityManager.clear();
        Settlement original = createdBySeller(settlementCreationService.createMonthlySettlements(2026, 6, ADMIN))
            .get(SELLER_MIXED);
        // 품목이 사라진 상황(데이터 정정)
        disableFkChecks();
        entityManager.getEntityManager().createNativeQuery("DELETE FROM order_item WHERE id = " + itemId).executeUpdate();
        entityManager.flush();
        entityManager.clear();

        SettlementRegenerateResult result = settlementCreationService.regenerate(original.getId(), "품목 삭제", ADMIN);

        assertThat(result.deletedSettlementId()).isEqualTo(original.getId());
        assertThat(result.regenerated()).isNull();
        assertThat(settlementRepository.count()).isZero();
        assertThat(settlementItemRepository.count()).isZero();
        // CREATE 감사는 최초 생성 1건뿐(재생성 없음)·DELETE 1건
        verify(auditRecorder, times(1)).record(eq(ADMIN), eq(AuditLogAction.CREATE), eq(PolymorphicTargetType.SETTLEMENT),
            any(), anyMap(), anyMap());
        verify(auditRecorder, times(1)).record(eq(ADMIN), eq(AuditLogAction.DELETE), eq(PolymorphicTargetType.SETTLEMENT),
            eq(original.getId()), anyMap(), anyMap());

        // CONFIRMED는 재생성 불가(422)·미존재 404
        Settlement confirmed = settlementRepository.saveAndFlush(
            Settlement.create(SELLER_MIXED, PERIOD_START, PERIOD_END, 1L, 0L, 0L, LocalDate.of(2026, 7, 20)));
        confirmed.markConfirmed();
        settlementRepository.saveAndFlush(confirmed);
        assertThatThrownBy(() -> settlementCreationService.regenerate(confirmed.getId(), "사유", ADMIN))
            .isInstanceOf(SettlementInvalidStateException.class);
        assertThatThrownBy(() -> settlementCreationService.regenerate(999_999L, "사유", ADMIN))
            .isInstanceOf(SettlementNotFoundException.class);
    }
}

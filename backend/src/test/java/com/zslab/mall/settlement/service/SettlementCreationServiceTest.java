package com.zslab.mall.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.exception.SettlementPeriodInvalidException;
import com.zslab.mall.settlement.repository.SettlementRepository;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * {@link SettlementCreationService} @DataJpaTest(Track 48 P3). 실 집계 쿼리(P2)를 MariaDB에 대해 구동해 seller별 병합·
 * fee 버림·net 음수·계좌부재 skip·재실행 멱등을 검증한다. 서비스는 {@link Import}로 컨텍스트에 올린다.
 *
 * <p>시드 시각은 {@code LocalDateTime} 바인딩 파라미터로 넣는다(P2 트랩 대응 — raw 리터럴은 KST 원문 저장·조회 파라미터는
 * 세션TZ 변환으로 경계 어긋남). FOREIGN_KEY_CHECKS=0 시드 후 @AfterEach로 복구(커넥션 풀 누수 방지).
 */
@Import(SettlementCreationService.class)
class SettlementCreationServiceTest extends Batch1DataJpaTestBase {

    private static final long SELLER_MIXED = 9401L;    // 매출+환불
    private static final long SELLER_SALES_ONLY = 9402L; // 매출만
    private static final long SELLER_REFUND_ONLY = 9403L; // 환불만(net 음수)
    private static final long SELLER_NO_ACCOUNT = 9404L;  // 매출 있으나 정산계좌 없음 → skip
    private static final LocalDateTime IN_PERIOD = LocalDateTime.of(2026, 6, 15, 12, 0, 0);

    @Autowired
    private SettlementCreationService settlementCreationService;
    @Autowired
    private SettlementRepository settlementRepository;

    private int seq = 0;

    @AfterEach
    void restoreForeignKeyChecks() {
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    private void disableFkChecks() {
        entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
    }

    private void insertSeller(long id, int commissionRate) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
            + "VALUES (:id, :pid, '정산셀러', '대표', 'ACTIVE', :rate, NOW(6), NOW(6))");
        query.setParameter("id", id);
        query.setParameter("pid", String.format("slr_%026d", id));
        query.setParameter("rate", commissionRate);
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

    private void insertConfirmedOrderItem(long sellerId, long totalPrice, LocalDateTime confirmedAt) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO order_item "
            + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
            + "item_status, confirmed_at, created_at, updated_at) "
            + "VALUES (:pid, 1, 1, 1, :seller, 1, :total, :total, 'CONFIRMED', :confirmedAt, NOW(6), NOW(6))");
        query.setParameter("pid", String.format("oit_%026d", ++seq));
        query.setParameter("seller", sellerId);
        query.setParameter("total", totalPrice);
        query.setParameter("confirmedAt", confirmedAt);
        query.executeUpdate();
    }

    private long insertOrderItemForClaim(long sellerId) {
        disableFkChecks();
        Query query = entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO order_item "
            + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, "
            + "item_status, created_at, updated_at) "
            + "VALUES (:pid, 1, 1, 1, :seller, 1, 1000, 1000, 'RETURNED', NOW(6), NOW(6))");
        query.setParameter("pid", String.format("oit_%026d", ++seq));
        query.setParameter("seller", sellerId);
        query.executeUpdate();
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
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
        return ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private void insertCompletedRefund(long claimId, long amount, LocalDateTime refundedAt) {
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
    }

    /** seller에 대해 (claim 경유) COMPLETED 환불 1건을 시드한다. */
    private void seedRefundForSeller(long sellerId, long amount, LocalDateTime refundedAt) {
        long orderItemId = insertOrderItemForClaim(sellerId);
        long claimId = insertClaim(orderItemId);
        insertCompletedRefund(claimId, amount, refundedAt);
    }

    private Map<Long, Settlement> createdBySeller(SettlementBatchResult result) {
        return result.created().stream()
            .collect(Collectors.toMap(Settlement::getSellerId, Function.identity()));
    }

    @Test
    @DisplayName("createMonthlySettlements: 매출/환불/양쪽 seller 병합·fee 버림·net 음수·commissionRate 스냅샷·계좌부재 skip")
    void createMonthlySettlements_mergesAndComputes() {
        insertSeller(SELLER_MIXED, 1000);        // 10%
        insertSeller(SELLER_SALES_ONLY, 500);    // 5%
        insertSeller(SELLER_REFUND_ONLY, 1000);
        insertSeller(SELLER_NO_ACCOUNT, 1000);
        insertPrimaryBankAccount(SELLER_MIXED);
        insertPrimaryBankAccount(SELLER_SALES_ONLY);
        insertPrimaryBankAccount(SELLER_REFUND_ONLY);
        // SELLER_NO_ACCOUNT: 정산계좌 미시드 → skip 대상

        insertConfirmedOrderItem(SELLER_MIXED, 10_333L, IN_PERIOD);       // fee 버림: 10333*1000/10000=1033
        insertConfirmedOrderItem(SELLER_SALES_ONLY, 20_000L, IN_PERIOD);  // fee 20000*500/10000=1000
        insertConfirmedOrderItem(SELLER_NO_ACCOUNT, 50_000L, IN_PERIOD);  // skip(계좌 부재)
        seedRefundForSeller(SELLER_MIXED, 333L, IN_PERIOD);              // net=10333-1033-333=8967
        seedRefundForSeller(SELLER_REFUND_ONLY, 5_000L, IN_PERIOD);       // gross 0·net=-5000
        entityManager.flush();
        entityManager.clear();

        SettlementBatchResult result = settlementCreationService.createMonthlySettlements(2026, 6);

        Map<Long, Settlement> bySeller = createdBySeller(result);
        assertThat(bySeller).containsOnlyKeys(SELLER_MIXED, SELLER_SALES_ONLY, SELLER_REFUND_ONLY);

        Settlement mixed = bySeller.get(SELLER_MIXED);
        assertThat(mixed.getGrossAmount()).isEqualTo(10_333L);
        assertThat(mixed.getFeeAmount()).isEqualTo(1_033L);       // 버림 검증
        assertThat(mixed.getCommissionRate()).isEqualTo(1000);
        assertThat(mixed.getRefundAmount()).isEqualTo(333L);
        assertThat(mixed.getNetAmount()).isEqualTo(8_967L);

        Settlement salesOnly = bySeller.get(SELLER_SALES_ONLY);
        assertThat(salesOnly.getGrossAmount()).isEqualTo(20_000L);
        assertThat(salesOnly.getFeeAmount()).isEqualTo(1_000L);
        assertThat(salesOnly.getCommissionRate()).isEqualTo(500);
        assertThat(salesOnly.getRefundAmount()).isEqualTo(0L);
        assertThat(salesOnly.getNetAmount()).isEqualTo(19_000L);

        Settlement refundOnly = bySeller.get(SELLER_REFUND_ONLY);
        assertThat(refundOnly.getGrossAmount()).isEqualTo(0L);
        assertThat(refundOnly.getFeeAmount()).isEqualTo(0L);
        assertThat(refundOnly.getRefundAmount()).isEqualTo(5_000L);
        assertThat(refundOnly.getNetAmount()).isEqualTo(-5_000L);  // net 음수 허용
    }

    @Test
    @DisplayName("createMonthlySettlements: month 범위 위반(0·13) → SettlementPeriodInvalidException")
    void createMonthlySettlements_invalidMonth_throws() {
        assertThatThrownBy(() -> settlementCreationService.createMonthlySettlements(2026, 0))
            .isInstanceOf(SettlementPeriodInvalidException.class);
        assertThatThrownBy(() -> settlementCreationService.createMonthlySettlements(2026, 13))
            .isInstanceOf(SettlementPeriodInvalidException.class);
    }

    @Test
    @DisplayName("createMonthlySettlements: 재실행 시 기존 seller skip(중복 미생성·멱등)")
    void createMonthlySettlements_rerunSkips() {
        insertSeller(SELLER_MIXED, 1000);
        insertPrimaryBankAccount(SELLER_MIXED);
        insertConfirmedOrderItem(SELLER_MIXED, 10_000L, IN_PERIOD);
        entityManager.flush();
        entityManager.clear();

        SettlementBatchResult first = settlementCreationService.createMonthlySettlements(2026, 6);
        assertThat(first.created()).hasSize(1);

        SettlementBatchResult second = settlementCreationService.createMonthlySettlements(2026, 6);
        assertThat(second.created()).isEmpty();          // 전부 skip
        assertThat(settlementRepository.count()).isEqualTo(1L);  // 중복 미생성
    }
}

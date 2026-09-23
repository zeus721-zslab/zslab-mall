package com.zslab.mall.settlement.repository;

import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.enums.SettlementStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 정산 Repository.
 */
public interface SettlementRepository extends JpaRepository<Settlement, Long>, JpaSpecificationExecutor<Settlement> {

    /**
     * 같은 seller·정산 기간의 Settlement가 이미 존재하는지 여부(파생 쿼리·Track 48 P2). P1에서 신설한
     * {@code uk_settlement_seller_period(seller_id, period_start, period_end)}와 짝이며, 정산 배치가 생성 전 선확인해
     * 중복 정산을 차단한다(saveAndFlush DataIntegrityViolation→409 이중화는 P3).
     */
    boolean existsBySellerIdAndPeriodStartAndPeriodEnd(
            Long sellerId, LocalDateTime periodStart, LocalDateTime periodEnd);

    /**
     * 전이 대상 Settlement를 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 조회한다(Track 49). 상태 전이(confirm·pay)의
     * 동시 실행을 행 단위로 직렬화해 이중 전이를 차단한다({@code InventoryRepository.findByVariantIdForUpdate}·D-101 house
     * pattern 준용). 모든 변수는 :id 바인딩이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Settlement s WHERE s.id = :id")
    Optional<Settlement> findByIdForUpdate(@Param("id") Long id);

    /**
     * 다음 정산으로 이월할 음수 정산을 조회한다(Track 104-3b 결정 ⑧). 대상 = 확정(CONFIRMED)됐지만 순지급액이 음수라 지급이 막힌 정산 중
     * 기간 말이 새 정산 기간 말 이전이고 아직 이월 품목으로 편입되지 않은 것. PENDING 음수 정산은 재생성으로 id가 바뀔 수 있어 확정 뒤에만
     * 이월한다. 편입 여부는 settlement_item (CARRYOVER, source_id = 원 정산 id) 전역 UNIQUE 키로 판정한다. sellerId가 null이면 전 셀러.
     * 모든 변수는 :periodEnd·:sellerId 바인딩만 사용하며 SQL injection 위험이 없다.
     */
    @Query("SELECT s.id AS settlementId, s.sellerId AS sellerId, s.netAmount AS netAmount, s.periodEnd AS periodEnd "
            + "FROM Settlement s "
            + "WHERE s.status = com.zslab.mall.settlement.enums.SettlementStatus.CONFIRMED "
            + "AND s.netAmount < 0 "
            + "AND s.periodEnd <= :periodEnd "
            + "AND (:sellerId IS NULL OR s.sellerId = :sellerId) "
            + "AND NOT EXISTS (SELECT 1 FROM SettlementItem si "
            + "WHERE si.itemType = com.zslab.mall.settlement.enums.SettlementItemType.CARRYOVER AND si.sourceId = s.id) "
            + "ORDER BY s.sellerId, s.periodEnd, s.id")
    List<SettlementCarryoverSourceProjection> findCarryoverSources(
            @Param("periodEnd") LocalDateTime periodEnd, @Param("sellerId") Long sellerId);

    /**
     * 해당 월(기간 정확 일치) 상태별 건수·금액 합(Track 85 관리자 목록 합계). 이미 다음 정산에 이월(CARRYOVER 품목 출처 = 이 정산 id)된
     * 음수 정산의 순지급액은 net 합에서 뺀다 — 그 부족분은 이월받은 정산의 net에 반영돼 있어 두 번 세면 이중 차감이다(Track 104-3b).
     * 건수·다른 금액 합은 그대로다. CARRYOVER 조인은 (item_type, source_id) 전역 UNIQUE라 정산당 최대 1행이다.
     * 모든 변수는 :periodStart·:periodEnd 바인딩이다.
     */
    @Query("SELECT s.status AS status, COUNT(s) AS settlementCount, COALESCE(SUM(s.grossAmount), 0) AS grossAmount, "
            + "COALESCE(SUM(s.feeAmount), 0) AS feeAmount, COALESCE(SUM(s.refundAmount), 0) AS refundAmount, "
            + "COALESCE(SUM(s.carryoverAmount), 0) AS carryoverAmount, "
            + "COALESCE(SUM(CASE WHEN carried.id IS NULL THEN s.netAmount ELSE 0 END), 0) AS netAmount FROM Settlement s "
            + "LEFT JOIN SettlementItem carried ON carried.itemType = com.zslab.mall.settlement.enums.SettlementItemType.CARRYOVER "
            + "AND carried.sourceId = s.id "
            + "WHERE s.periodStart = :periodStart AND s.periodEnd = :periodEnd GROUP BY s.status")
    List<SettlementStatusTotalProjection> sumByStatusForPeriod(
            @Param("periodStart") LocalDateTime periodStart, @Param("periodEnd") LocalDateTime periodEnd);

    /** 셀러 월별 이력(관리자·최신 기간순은 Pageable 정렬). */
    Page<Settlement> findBySellerId(Long sellerId, Pageable pageable);

    /** 셀러 공개 목록(CONFIRMED·PAID만·Track 85). */
    Page<Settlement> findBySellerIdAndStatusIn(Long sellerId, Collection<SettlementStatus> statuses, Pageable pageable);

    /** 셀러 공개 단건(본인·CONFIRMED·PAID만·그 외 empty → 404 통일). */
    Optional<Settlement> findByIdAndSellerIdAndStatusIn(Long id, Long sellerId, Collection<SettlementStatus> statuses);

    /** 셀러의 특정 상태 정산 건수(셀러 내 정보·대시보드 PENDING 건수). 종료 가드 G1은 {@link #countNotCarriedOverBySellerIdAndStatusIn}. 파생 쿼리 바인딩. */
    long countBySellerIdAndStatusIn(Long sellerId, Collection<SettlementStatus> statuses);

    /**
     * 셀러의 특정 상태 정산 중 다음 정산에 이월되지 않은 건수(Track 104-4 종료 가드 G1). 이미 이월(CARRYOVER 품목 출처 = 이 정산 id)된 음수
     * CONFIRMED 정산은 지급 전이가 막힌 채 CONFIRMED로 남지만 그 부족분은 이월받은 정산이 떠안으므로 미지급으로 세지 않는다 — 판정은
     * {@link #sumByStatusForSeller}와 같은 CARRYOVER 조인(정산당 최대 1행). 모든 변수는 :sellerId·:statuses 바인딩이다.
     */
    @Query("SELECT COUNT(s) FROM Settlement s "
            + "LEFT JOIN SettlementItem carried ON carried.itemType = com.zslab.mall.settlement.enums.SettlementItemType.CARRYOVER "
            + "AND carried.sourceId = s.id "
            + "WHERE s.sellerId = :sellerId AND s.status IN :statuses AND carried.id IS NULL")
    long countNotCarriedOverBySellerIdAndStatusIn(
            @Param("sellerId") Long sellerId, @Param("statuses") Collection<SettlementStatus> statuses);

    /**
     * 셀러 정산 상태별 건수·금액 합(Track 89-D 관리자 셀러 상세·전 기간). 이미 이월된 음수 정산의 순지급액은 net 합에서 뺀다
     * ({@link #sumByStatusForPeriod}와 같은 이유·Track 104-3b). 건수는 그대로다. 모든 변수는 :sellerId 바인딩이다.
     */
    @Query("SELECT s.status AS status, COUNT(s) AS settlementCount, COALESCE(SUM(s.grossAmount), 0) AS grossAmount, "
            + "COALESCE(SUM(s.feeAmount), 0) AS feeAmount, COALESCE(SUM(s.refundAmount), 0) AS refundAmount, "
            + "COALESCE(SUM(s.carryoverAmount), 0) AS carryoverAmount, "
            + "COALESCE(SUM(CASE WHEN carried.id IS NULL THEN s.netAmount ELSE 0 END), 0) AS netAmount FROM Settlement s "
            + "LEFT JOIN SettlementItem carried ON carried.itemType = com.zslab.mall.settlement.enums.SettlementItemType.CARRYOVER "
            + "AND carried.sourceId = s.id "
            + "WHERE s.sellerId = :sellerId GROUP BY s.status")
    List<SettlementStatusTotalProjection> sumByStatusForSeller(@Param("sellerId") Long sellerId);

    /**
     * 정산이 이 계좌 행을 지급 스냅샷으로 참조하는지(Track 89-F·D-188 계좌 수정 409 판정). 상태 무관 — bank_account_id는 markPaid에서만
     * 설정되므로 실질적으로 PAID 정산이지만, 판정 기준은 "settlement 행이 이 id를 가리키는가"다. 파생 쿼리 바인딩.
     */
    boolean existsByBankAccountId(Long bankAccountId);

    /**
     * 정산이 지급 계좌로 참조하는 계좌 id 집합(Track 89-F 외부 검토 Q6·상세 계좌 목록 미리보기·배치 1회). {@link #existsByBankAccountId}와
     * 같은 기준(상태 무관·bank_account_id 일치)이라 미리보기 = 수정 409 판정이다. 모든 변수는 :bankAccountIds 바인딩이다.
     */
    @Query("SELECT DISTINCT s.bankAccountId FROM Settlement s WHERE s.bankAccountId IN :bankAccountIds")
    List<Long> findReferencedBankAccountIds(@Param("bankAccountIds") Collection<Long> bankAccountIds);
}

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

    /** 해당 월(기간 정확 일치) 상태별 건수·금액 합(Track 85 관리자 목록 합계). 모든 변수는 :periodStart·:periodEnd 바인딩이다. */
    @Query("SELECT s.status AS status, COUNT(s) AS settlementCount, COALESCE(SUM(s.grossAmount), 0) AS grossAmount, "
            + "COALESCE(SUM(s.feeAmount), 0) AS feeAmount, COALESCE(SUM(s.refundAmount), 0) AS refundAmount, "
            + "COALESCE(SUM(s.netAmount), 0) AS netAmount FROM Settlement s "
            + "WHERE s.periodStart = :periodStart AND s.periodEnd = :periodEnd GROUP BY s.status")
    List<SettlementStatusTotalProjection> sumByStatusForPeriod(
            @Param("periodStart") LocalDateTime periodStart, @Param("periodEnd") LocalDateTime periodEnd);

    /** 셀러 월별 이력(관리자·최신 기간순은 Pageable 정렬). */
    Page<Settlement> findBySellerId(Long sellerId, Pageable pageable);

    /** 셀러 공개 목록(CONFIRMED·PAID만·Track 85). */
    Page<Settlement> findBySellerIdAndStatusIn(Long sellerId, Collection<SettlementStatus> statuses, Pageable pageable);

    /** 셀러 공개 단건(본인·CONFIRMED·PAID만·그 외 empty → 404 통일). */
    Optional<Settlement> findByIdAndSellerIdAndStatusIn(Long id, Long sellerId, Collection<SettlementStatus> statuses);

    /** 셀러의 특정 상태 정산 건수(Track 89-D 종료 가드 G1·미지급 = PENDING·CONFIRMED). 파생 쿼리 바인딩. */
    long countBySellerIdAndStatusIn(Long sellerId, Collection<SettlementStatus> statuses);

    /** 셀러 정산 상태별 건수·금액 합(Track 89-D 관리자 셀러 상세·전 기간). 모든 변수는 :sellerId 바인딩이다. */
    @Query("SELECT s.status AS status, COUNT(s) AS settlementCount, COALESCE(SUM(s.grossAmount), 0) AS grossAmount, "
            + "COALESCE(SUM(s.feeAmount), 0) AS feeAmount, COALESCE(SUM(s.refundAmount), 0) AS refundAmount, "
            + "COALESCE(SUM(s.netAmount), 0) AS netAmount FROM Settlement s "
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

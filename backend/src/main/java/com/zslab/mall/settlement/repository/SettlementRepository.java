package com.zslab.mall.settlement.repository;

import com.zslab.mall.settlement.entity.Settlement;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 정산 Repository.
 */
public interface SettlementRepository extends JpaRepository<Settlement, Long> {

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
}

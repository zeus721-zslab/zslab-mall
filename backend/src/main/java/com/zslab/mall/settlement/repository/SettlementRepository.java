package com.zslab.mall.settlement.repository;

import com.zslab.mall.settlement.entity.Settlement;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

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
}

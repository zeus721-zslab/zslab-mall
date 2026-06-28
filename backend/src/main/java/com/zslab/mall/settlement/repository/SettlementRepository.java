package com.zslab.mall.settlement.repository;

import com.zslab.mall.settlement.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 정산 Repository.
 */
public interface SettlementRepository extends JpaRepository<Settlement, Long> {
}

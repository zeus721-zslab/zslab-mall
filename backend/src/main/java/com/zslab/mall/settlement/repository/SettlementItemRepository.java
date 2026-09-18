package com.zslab.mall.settlement.repository;

import com.zslab.mall.settlement.entity.SettlementItem;
import com.zslab.mall.settlement.enums.SettlementItemType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 정산 품목 스냅샷 Repository(Track 85).
 */
public interface SettlementItemRepository extends JpaRepository<SettlementItem, Long> {

    /** 정산 품목 페이지(전체 type·정렬은 Pageable이 occurred_at 오름차순으로 고정). */
    Page<SettlementItem> findBySettlementId(Long settlementId, Pageable pageable);

    /** 정산 품목 페이지(type 필터). */
    Page<SettlementItem> findBySettlementIdAndItemType(Long settlementId, SettlementItemType itemType, Pageable pageable);

    /** 여러 정산의 type별 품목 건수(목록 enrich·N+1 회피). 모든 변수는 :ids·:type 바인딩이다. */
    @Query("SELECT si.settlementId AS settlementId, COUNT(si) AS itemCount FROM SettlementItem si "
            + "WHERE si.settlementId IN :ids AND si.itemType = :type GROUP BY si.settlementId")
    List<SettlementItemCountProjection> countByTypeGrouped(
            @Param("ids") Collection<Long> ids, @Param("type") SettlementItemType type);

    /** 재생성 시 품목 일괄 삭제(헤더 삭제보다 먼저·FK RESTRICT). 모든 변수는 :settlementId 바인딩이다. */
    @Modifying
    @Query("DELETE FROM SettlementItem si WHERE si.settlementId = :settlementId")
    int deleteBySettlementId(@Param("settlementId") Long settlementId);
}

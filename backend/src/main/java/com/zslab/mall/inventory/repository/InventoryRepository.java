package com.zslab.mall.inventory.repository;

import com.zslab.mall.inventory.entity.Inventory;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 재고 Repository(Track 4 read-only·D-57). variant 기준 조회만 제공한다(쓰기·차감 메서드는 Track 7 이연).
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /** variant 1건의 재고를 조회한다(재결제 단건 재검증·D-51). */
    Optional<Inventory> findByVariantId(Long variantId);

    /** 여러 variant의 재고를 일괄 조회한다(주문 품목 재고 검증 배치·N+1 회피). */
    List<Inventory> findByVariantIdIn(Collection<Long> variantIds);

    /**
     * variant 재고 1행을 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 조회한다(Track 17 D-101 §4). Inventory 도메인
     * 행위(예약·해제·차감·복구) 진입 시 동시 갱신을 직렬화해 oversell을 방지하며 INV-1·INV-3·INV-4를 사전 보호한다.
     * 모든 변수는 :variantId 바인딩이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.variantId = :variantId")
    Optional<Inventory> findByVariantIdForUpdate(@Param("variantId") Long variantId);
}

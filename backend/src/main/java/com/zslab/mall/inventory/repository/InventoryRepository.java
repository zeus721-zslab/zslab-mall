package com.zslab.mall.inventory.repository;

import com.zslab.mall.inventory.entity.Inventory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 재고 Repository(Track 4 read-only·D-57). variant 기준 조회만 제공한다(쓰기·차감 메서드는 Track 7 이연).
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /** variant 1건의 재고를 조회한다(재결제 단건 재검증·D-51). */
    Optional<Inventory> findByVariantId(Long variantId);

    /** 여러 variant의 재고를 일괄 조회한다(주문 품목 재고 검증 배치·N+1 회피). */
    List<Inventory> findByVariantIdIn(Collection<Long> variantIds);
}

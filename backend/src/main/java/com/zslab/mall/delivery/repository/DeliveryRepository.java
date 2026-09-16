package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.entity.Delivery;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 배송 Repository.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByPublicId(String publicId);

    /** 교환 배송 이중 등록 멱등 가드용(D-99 Q11). claim_id 연결된 Delivery 존재 시 재등록을 차단한다. */
    Optional<Delivery> findByClaimId(Long claimId);

    /** 관리자 주문 목록·상세 배치 enrich(Track 79 D-168·N+1 회피). 항목별 최신 행이 앞에 오도록 id 내림차순. */
    List<Delivery> findByOrderItemIdInOrderByIdDesc(Collection<Long> orderItemIds);
}

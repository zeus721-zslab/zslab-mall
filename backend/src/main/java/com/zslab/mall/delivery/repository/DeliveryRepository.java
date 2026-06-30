package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.entity.Delivery;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 배송 Repository.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByPublicId(String publicId);

    /** 교환 배송 이중 등록 멱등 가드용(D-99 Q11). claim_id 연결된 Delivery 존재 시 재등록을 차단한다. */
    Optional<Delivery> findByClaimId(Long claimId);
}

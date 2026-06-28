package com.zslab.mall.delivery.repository;

import com.zslab.mall.delivery.entity.Delivery;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 배송 Repository.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByPublicId(String publicId);
}

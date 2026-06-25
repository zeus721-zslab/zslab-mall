package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.OrderShippingSnapshot;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 배송지 스냅샷 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리만).
 */
public interface OrderShippingSnapshotRepository extends JpaRepository<OrderShippingSnapshot, Long> {

    Optional<OrderShippingSnapshot> findByOrderId(Long orderId);
}

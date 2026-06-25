package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리만).
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByPublicId(String publicId);

    Optional<Order> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);
}

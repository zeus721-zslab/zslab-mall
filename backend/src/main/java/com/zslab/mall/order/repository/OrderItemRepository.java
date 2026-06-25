package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.OrderItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 품목 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리만).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    List<OrderItem> findByOrderIdIn(Collection<Long> orderIds);
}

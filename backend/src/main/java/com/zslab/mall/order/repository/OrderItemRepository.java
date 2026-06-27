package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.OrderItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 품목 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리 + 경량 projection).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    List<OrderItem> findByOrderIdIn(Collection<Long> orderIds);

    /**
     * 주문 품목의 소속 order_id만 조회한다(Track 5 환불 흐름·Claim → Payment 해소용 경량 projection).
     * OrderItem.order는 getter를 노출하지 않으므로(Aggregate 단방향) FK 컬럼 값만 읽는다. 모든 변수는 :id 바인딩이다.
     */
    @Query("SELECT oi.order.id FROM OrderItem oi WHERE oi.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);
}

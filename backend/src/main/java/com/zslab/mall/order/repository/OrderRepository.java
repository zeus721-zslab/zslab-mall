package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리 + fetch join 1건).
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByPublicId(String publicId);

    Optional<Order> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    /**
     * Order를 items와 함께 fetch join으로 조회한다(D-33 Lazy 안전망). PaymentCompleted 소비 시
     * markPaid가 items를 순회하므로 동일 트랜잭션에서 선로딩해 LazyInitializationException을 차단한다.
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);
}

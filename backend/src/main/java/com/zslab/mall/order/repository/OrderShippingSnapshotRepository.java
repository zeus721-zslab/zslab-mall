package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.OrderShippingSnapshot;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 배송지 스냅샷 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리만).
 */
public interface OrderShippingSnapshotRepository extends JpaRepository<OrderShippingSnapshot, Long> {

    Optional<OrderShippingSnapshot> findByOrderId(Long orderId);

    /**
     * 한 주문의 배송지 스냅샷을 물리삭제한다(FE-12c-2·미결제 종료 주문 hard delete 자식 정리). 1:1이나 벌크 DELETE로 통일하며
     * 삭제 건수를 반환한다. 부모 order 삭제에 선행해야 한다(fk_order_shipping_snapshot_order RESTRICT). 모든 변수는 :orderId 바인딩이다.
     */
    @Modifying
    @Query("DELETE FROM OrderShippingSnapshot s WHERE s.order.id = :orderId")
    int deleteByOrderId(@Param("orderId") Long orderId);

    /**
     * 여러 주문의 배송지 스냅샷을 주문 id 키와 함께 한 번에 읽는다(Track 89-B 관리자 배송 목록·상세 배치 enrich·N+1 회피).
     * 모든 변수는 :orderIds 바인딩이다.
     */
    @Query("SELECT s.order.id AS orderId, s.recipientName AS recipientName, s.recipientPhone AS recipientPhone, "
            + "s.zonecode AS zonecode, s.addressRoad AS addressRoad, s.addressJibun AS addressJibun, "
            + "s.addressDetail AS addressDetail, s.deliveryMemo AS deliveryMemo "
            + "FROM OrderShippingSnapshot s WHERE s.order.id IN :orderIds")
    List<OrderShippingSnapshotProjection> findProjectionsByOrderIdIn(@Param("orderIds") Collection<Long> orderIds);
}

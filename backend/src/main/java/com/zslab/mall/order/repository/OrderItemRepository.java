package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 품목 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리 + 경량 projection).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    List<OrderItem> findByOrderIdIn(Collection<Long> orderIds);

    /**
     * 한 주문의 OrderItem을 일괄 물리삭제한다(FE-12c-2·미결제 종료 주문 hard delete 자식 정리). 벌크 DELETE로 1회 실행하며
     * 삭제 건수를 반환한다. 삭제는 부모 order 삭제에 선행해야 한다(fk_order_item_order RESTRICT). 모든 변수는 :orderId 바인딩이다.
     */
    @Modifying
    @Query("DELETE FROM OrderItem oi WHERE oi.order.id = :orderId")
    int deleteByOrderId(@Param("orderId") Long orderId);

    /**
     * OrderItem을 public_id(oit_)로 조회한다. 외부 요청의 public_id를 BIGINT id로 해소하는 진입점 패턴이다
     * (D-64·D-65 정합·OrderRepository.findByPublicId와 1:1). {@code ClaimService.request}가 소유권·전이 검증에 사용한다.
     */
    Optional<OrderItem> findByPublicId(String publicId);

    /**
     * 주문 품목의 소속 order_id만 조회한다(Track 5 환불 흐름·Claim → Payment 해소용 경량 projection).
     * OrderItem.order는 getter를 노출하지 않으므로(Aggregate 단방향) FK 컬럼 값만 읽는다. 모든 변수는 :id 바인딩이다.
     */
    @Query("SELECT oi.order.id FROM OrderItem oi WHERE oi.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    /**
     * 정산 기간 내 구매확정(CONFIRMED) 품목의 총 매출(total_price 합)을 seller별로 집계한다(Track 48 P2·정산 gross 소스).
     * 기간 기준은 {@code confirmed_at}이며 경계는 양끝 포함({@code >= periodStart AND <= periodEnd})이다.
     * {@code confirmed_at IS NULL}(미확정) 행은 범위 비교가 false로 평가돼 자연 제외된다.
     *
     * <p>{@code status}는 enum 바인딩 파라미터로 전달한다(@Enumerated(STRING) 정합·JPQL enum 리터럴 ordinal 비교 함정 회피).
     * 모든 변수는 :status·:periodStart·:periodEnd 바인딩만 사용하며 SQL injection 위험이 없다.
     */
    @Query("SELECT oi.sellerId AS sellerId, COALESCE(SUM(oi.totalPrice), 0) AS grossAmount "
            + "FROM OrderItem oi "
            + "WHERE oi.itemStatus = :status "
            + "AND oi.confirmedAt >= :periodStart "
            + "AND oi.confirmedAt <= :periodEnd "
            + "GROUP BY oi.sellerId")
    List<SellerGrossProjection> aggregateGrossBySeller(
            @Param("status") OrderItemStatus status,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd);

    /**
     * buyer의 생애 누적 구매액(구매확정 품목 total_price 합)을 집계한다(Track 51 등급 산정 입력·recon-report §R1).
     * buyer_id는 Order에만 존재하므로 {@code oi.order.buyerId}로 조인 네비게이션한다(OrderItem은 buyer_id 미보유).
     * 기간 필터 없음(생애 누적)이며 {@code COALESCE(...,0)}로 구매확정 이력이 없는 buyer도 0을 반환한다.
     *
     * <p>환불 차감 없음: 환불·취소·반품·교환 품목은 CANCELLED·RETURNED·EXCHANGED(종결)로 전이돼 CONFIRMED와 상호배타이며,
     * CONFIRMED 이후 클레임은 전건 차단된다(D-88 Q3). 따라서 {@code status = CONFIRMED} 필터가 환불 가치를 원천 배제한다.
     * {@code status}는 enum 바인딩 파라미터로 전달한다(seller 집계와 동일·ordinal 비교 함정 회피). 모든 변수는 :바인딩이다.
     */
    @Query("SELECT COALESCE(SUM(oi.totalPrice), 0) "
            + "FROM OrderItem oi "
            + "WHERE oi.order.buyerId = :buyerId "
            + "AND oi.itemStatus = :status")
    long sumConfirmedTotalPriceByBuyerId(
            @Param("buyerId") Long buyerId,
            @Param("status") OrderItemStatus status);
}

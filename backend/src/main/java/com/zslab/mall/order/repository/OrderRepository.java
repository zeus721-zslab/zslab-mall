package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리 + fetch join).
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

    /**
     * public_id로 Order를 items·shippingSnapshot과 함께 fetch join 조회한다(GET 단건 상세·재결제 재검증).
     * 트랜잭션 밖(CheckoutService)에서도 연관 접근이 안전하도록 선로딩한다.
     */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items LEFT JOIN FETCH o.shippingSnapshot "
            + "WHERE o.publicId = :publicId")
    Optional<Order> findByPublicIdWithItems(@Param("publicId") String publicId);

    /** 여러 Order를 items와 함께 일괄 fetch join 조회한다(목록 enrich·previewTitle/sellerCount·N+1 회피). */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id IN :ids")
    List<Order> findByIdInWithItems(@Param("ids") Collection<Long> ids);

    /** Buyer 본인 주문 목록(ordered_at DESC·D-42·D-54 페이징). items는 미포함(요약 enrich는 findByIdInWithItems로 별도). */
    Page<Order> findByBuyerIdOrderByOrderedAtDesc(Long buyerId, Pageable pageable);

    /**
     * Buyer 본인 주문 목록에서 특정 status를 제외하고 조회한다(FE-12c·미결제 종료 PAYMENT_EXPIRED 비노출).
     * 페이지 정합을 위해 DB 레벨에서 제외하며(서비스 필터 시 페이지 카운트 붕괴), 나머지 관습은 {@link #findByBuyerIdOrderByOrderedAtDesc}와 동일하다.
     */
    Page<Order> findByBuyerIdAndStatusNotOrderByOrderedAtDesc(Long buyerId, OrderStatus status, Pageable pageable);

    /**
     * 자동취소 대상(status·createdAt≤기준시각) 주문을 배치 상한으로 조회한다(D-153 Phase 1·ExpirePayment 배치 관습 정합).
     * 기준시각(threshold=now-유예)은 스케줄러가 계산해 전달하며 본 메서드는 파라미터만 받는다. items는 fetch join하지 않는다
     * — 취소 처리(OrderAutoCancelService.cancelOne)가 id별 독립 트랜잭션에서 {@link #findByIdWithItems}로 재조회하므로
     * (컬렉션 fetch join + 페이징 in-memory paging 함정 회피·ExpirePaymentScheduler 조회→재조회 패턴 미러).
     */
    List<Order> findByStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
            OrderStatus status, LocalDateTime threshold, Pageable pageable);

    /**
     * hard delete 대상(status·updatedAt≤기준시각) 주문을 배치 상한으로 조회한다(FE-12c-2·미결제 종료 주문 가비지 정리).
     * PAYMENT_EXPIRED 종료 후 재고 해제·유예(GRACE_DAYS) 경과 주문을 삭제 대상으로 선정한다. 기준 시각은 {@code createdAt}이
     * 아닌 {@code updatedAt}이다 — 미결제 종료(expirePayment)는 status만 세팅하고 이후 PAYMENT_EXPIRED에 updated_at을 바꾸는
     * 비즈니스 로직이 없어 updated_at이 종료 시각 근사이기 때문이다(FE-12c-2 불변식 2·expired_at 컬럼 미신설). items는 fetch join하지
     * 않는다 — 삭제 처리(ExpiredOrderCleanupService.cleanupOne)가 id별 독립 트랜잭션에서 재조회하므로(auto-cancel 배치 관습 미러).
     */
    List<Order> findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
            OrderStatus status, LocalDateTime threshold, Pageable pageable);

    /**
     * order 행을 id로 물리삭제한다(FE-12c-2·미결제 종료 주문 hard delete 부모 삭제). 자식(payment·snapshot·order_item)을
     * 벌크 삭제한 뒤 마지막에 호출한다(FK RESTRICT 순서). 자식 삭제 검사를 위해 로드한 managed OrderItem이 부모 삭제 flush
     * 순서와 충돌하지 않도록 {@code delete(entity)}(em.remove) 대신 벌크 DELETE로 통일한다. 모든 변수는 :orderId 바인딩이다.
     */
    @Modifying
    @Query("DELETE FROM Order o WHERE o.id = :orderId")
    int deleteByIdBulk(@Param("orderId") Long orderId);
}

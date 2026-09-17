package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.payment.enums.PaymentStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리 + fetch join).
 */
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByPublicId(String publicId);

    Optional<Order> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    /** 구매자에게 종결(statuses) 외 상태의 주문이 있는지(Track 84 탈퇴 가드·진행 중 주문 판정). 파생 쿼리 바인딩. */
    boolean existsByBuyerIdAndStatusNotIn(Long buyerId, Collection<OrderStatus> statuses);

    /**
     * 구매자별 결제 완료 최근 시각(MAX(paid_at))·관리자 회원 목록 페이지 배치 enrich(Track 84·N+1 회피). paid_at NULL 주문은 제외.
     * 모든 변수는 :buyerIds 바인딩만 사용하며 SQL injection 위험이 없다.
     */
    @Query("SELECT o.buyerId AS buyerId, MAX(o.paidAt) AS lastPaidAt FROM Order o "
            + "WHERE o.buyerId IN :buyerIds AND o.paidAt IS NOT NULL GROUP BY o.buyerId")
    List<BuyerLastPaidProjection> findLastPaidAtByBuyerIdIn(@Param("buyerIds") Collection<Long> buyerIds);

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
     * 비즈니스 로직이 없어 updated_at이 종료 시각 근사이기 때문이다(FE-12c-2 불변식 2·expired_at 컬럼 미신설). id만 조회한다
     * — 삭제 처리(ExpiredOrderCleanupService.cleanupOne)가 id별 독립 트랜잭션에서 재조회하므로(auto-cancel 배치 관습 미러).
     *
     * <p><b>삭제 불가 주문 제외(D-175·검수 5단계)</b>: cleanupOne이 skip·RESTRICT 실패로 끝날 주문(PENDING 결제 보유·품목에
     * delivery/claim 손자 보유 — refund는 claim 하위라 claim 제외로 흡수)은 조회 단계에서 NOT EXISTS로 걸러낸다. 걸러내지 않으면
     * updated_at 오름차순 배치 선두(100건)를 영구히 점유해 뒤의 정상 삭제 대상이 기아에 빠진다. cleanupOne의 가드 (0)(1)은 조회~처리
     * 사이 변경 대비로 유지한다. 모든 변수는 :status·:pendingPaymentStatus·:threshold 바인딩이다.
     */
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.updatedAt <= :threshold "
            + "AND NOT EXISTS (SELECT 1 FROM Payment p WHERE p.orderId = o.id AND p.status = :pendingPaymentStatus) "
            + "AND NOT EXISTS (SELECT 1 FROM Delivery d JOIN OrderItem oi ON oi.id = d.orderItemId WHERE oi.order.id = o.id) "
            + "AND NOT EXISTS (SELECT 1 FROM Claim c JOIN OrderItem oi2 ON oi2.id = c.orderItemId WHERE oi2.order.id = o.id) "
            + "ORDER BY o.updatedAt ASC, o.id ASC")
    List<Long> findExpiredCleanupCandidateIds(
            @Param("status") OrderStatus status,
            @Param("pendingPaymentStatus") PaymentStatus pendingPaymentStatus,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    /**
     * order 행을 id로 물리삭제한다(FE-12c-2·미결제 종료 주문 hard delete 부모 삭제). 자식(payment·snapshot·order_item)을
     * 벌크 삭제한 뒤 마지막에 호출한다(FK RESTRICT 순서). 자식 삭제 검사를 위해 로드한 managed OrderItem이 부모 삭제 flush
     * 순서와 충돌하지 않도록 {@code delete(entity)}(em.remove) 대신 벌크 DELETE로 통일한다. 모든 변수는 :orderId 바인딩이다.
     */
    @Modifying
    @Query("DELETE FROM Order o WHERE o.id = :orderId")
    int deleteByIdBulk(@Param("orderId") Long orderId);

    /**
     * 주문 상태를 조건부로 전이한다(Track 78 D-167·R3). {@code WHERE status = :current}로 동시 전이를 DB 레벨에서 직렬화하며,
     * 반환 영향 행이 1일 때만 호출부가 후속 이벤트(OrderTerminated)를 발행한다. {@code updatedAt}을 함께 갱신해 updated_at 기반
     * 스케줄러 임계(ExpiredOrderCleanup)가 전이 시각을 기준으로 동작하게 한다. {@code flushAutomatically}로 호출부 트랜잭션의
     * 선행 dirty 상태(Payment 전이 등)를 먼저 flush하고, {@code clearAutomatically}로 stale 관리 엔티티를 제거한다(호출부는
     * 실행 후 필요한 엔티티를 재조회한다). 모든 변수는 :orderId·:current·:next·:now 바인딩이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = :next, o.updatedAt = :now WHERE o.id = :orderId AND o.status = :current")
    int transitionStatus(
            @Param("orderId") Long orderId,
            @Param("current") OrderStatus current,
            @Param("next") OrderStatus next,
            @Param("now") LocalDateTime now);
}

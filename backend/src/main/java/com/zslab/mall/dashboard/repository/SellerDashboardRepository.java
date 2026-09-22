package com.zslab.mall.dashboard.repository;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 셀러 대시보드 전용 집계 리포지토리(Track 90-B-2). {@link AdminDashboardRepository}에 sellerId 인자를 주입하지 않고 신설한다 —
 * 관리자는 {@code Order} 루트라 {@code order.total_price}(타 셀러 몫·배송비·할인 포함)를 합산하지만 셀러 매출은 <b>자기 품목
 * {@code order_item.total_price} 합</b>이어야 하므로(혼합 주문·D-191) 루트가 {@link OrderItem}으로 다르다. CRUD를 노출하지 않는
 * {@link Repository} 마커만 상속한다(도메인 타입은 형식상 지정·JPQL은 여러 엔티티를 대상으로 한다).
 *
 * <p>모든 쿼리는 {@code oi.sellerId = :sellerId}로 범위를 잡는다(인덱스 ix_order_item_seller_status). 기간 경계는 서비스가 KST
 * LocalDateTime으로 계산해 바인딩하며 범위 조건은 반구간({@code >= from AND < to})이다. 미결제·만료 주문은 {@code paid_at}이 항상
 * null이라(PAYMENT_EXPIRED는 PENDING_PAYMENT에서만 전이) paid_at 조건만으로 제외된다. 네이티브 쿼리를 쓰지 않으며 모든 변수는
 * :바인딩만 사용해 SQL injection 위험이 없다.
 */
public interface SellerDashboardRepository extends Repository<OrderItem, Long> {

    /**
     * 기간 결제완료 주문의 자기 품목 매출 합·주문 수. orderCount는 COUNT(DISTINCT order) — 한 주문에 자기 품목이 여러 행이어도 1건이라
     * {@code /seller/order-items}의 totalCount(품목 행 수)와는 다르다. 품목 상태로 제외하지 않는다(관리자 findTopSellers와 같은 근거·
     * 환불은 summary.refund에서 별도 차감).
     */
    @Query("SELECT COUNT(DISTINCT o.id) AS orderCount, COALESCE(SUM(oi.totalPrice), 0) AS revenue "
            + "FROM OrderItem oi JOIN oi.order o "
            + "WHERE oi.sellerId = :sellerId AND o.paidAt >= :from AND o.paidAt < :to")
    SellerDashboardSalesProjection sumSales(@Param("sellerId") Long sellerId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 기간 완료 환불액 합(refunded_at 기준). Refund→Claim→OrderItem은 Long 참조라 theta-join 2단으로 셀러 품목 축에 맞춘다. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r, Claim c, OrderItem oi "
            + "WHERE r.claimId = c.id AND c.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "AND r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to")
    long sumRefund(@Param("sellerId") Long sellerId, @Param("status") RefundStatus status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(c) FROM Claim c, OrderItem oi WHERE oi.id = c.orderItemId AND oi.sellerId = :sellerId "
            + "AND c.status = :status")
    long countClaimsByStatus(@Param("sellerId") Long sellerId, @Param("status") ClaimStatus status);

    /** 배송 대기 = 송장 미등록 품목(PAID). 송장 등록이 PAID→PREPARING→SHIPPING을 1TX로 전이하므로 PAID가 곧 대기 상태다. */
    @Query("SELECT COUNT(oi) FROM OrderItem oi WHERE oi.sellerId = :sellerId AND oi.itemStatus = :status")
    long countOrderItemsByStatus(@Param("sellerId") Long sellerId, @Param("status") OrderItemStatus status);

    /**
     * 장기 배송중 = 자기 품목의 발송(OUTBOUND) 배송이 아직 SHIPPING이고 발송 시각이 {@code threshold} 이전인 건(Track 99 D-210).
     * 소유 판정은 order_item.seller_id 1-hop이며 관리자 집계와 같은 조건에 셀러 조건만 더한다. 모든 변수는 :바인딩이다.
     */
    @Query("SELECT COUNT(d) FROM Delivery d, OrderItem oi WHERE oi.id = d.orderItemId AND oi.sellerId = :sellerId "
            + "AND d.direction = :direction AND d.status = :status AND d.shippedAt <= :threshold")
    long countLongShipping(@Param("sellerId") Long sellerId, @Param("direction") DeliveryDirection direction,
            @Param("status") DeliveryStatus status, @Param("threshold") LocalDateTime threshold);

    /** 재고 임박 = 자기 상품(product.seller_id)의 가용 재고가 [min, max] 구간인 variant 수. 수동 품절분은 판매 의도가 없어 제외한다. */
    @Query("SELECT COUNT(i) FROM Inventory i, ProductVariant v, Product p "
            + "WHERE i.variantId = v.id AND v.productId = p.id AND p.sellerId = :sellerId "
            + "AND v.soldoutManual = false AND p.soldoutManual = false "
            + "AND i.quantityAvailable >= :min AND i.quantityAvailable <= :max")
    long countLowStock(@Param("sellerId") Long sellerId, @Param("min") int min, @Param("max") int max);

    /** 일별 결제완료 주문 수(DISTINCT)·자기 품목 매출 합. pattern은 서비스 상수 "%Y-%m-%d"만 전달한다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', o.paidAt, :pattern) AS bucket, COUNT(DISTINCT o.id) AS orderCount, "
            + "COALESCE(SUM(oi.totalPrice), 0) AS amount "
            + "FROM OrderItem oi JOIN oi.order o "
            + "WHERE oi.sellerId = :sellerId AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern) "
            + "ORDER BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern)")
    List<DashboardBucketProjection> sumSalesByBucket(@Param("sellerId") Long sellerId, @Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 최근 결제완료 자기 품목(paid_at 내림차순·동시각은 품목 id 내림차순). 건수는 Pageable로 제한한다. */
    @Query("SELECT oi.publicId AS orderItemPublicId, o.orderNo AS orderNo, oi.productName AS productName, "
            + "oi.optionLabel AS optionLabel, oi.quantity AS quantity, oi.totalPrice AS totalPrice, "
            + "oi.itemStatus AS itemStatus, o.paidAt AS paidAt "
            + "FROM OrderItem oi JOIN oi.order o WHERE oi.sellerId = :sellerId AND o.paidAt IS NOT NULL "
            + "ORDER BY o.paidAt DESC, oi.id DESC")
    List<SellerDashboardRecentOrderItemProjection> findRecentPaidOrderItems(@Param("sellerId") Long sellerId, Pageable pageable);

    /** 최근 자기 품목 클레임(requested_at 내림차순). 주문번호는 oi.order 네비게이션·구매자 정보는 싣지 않는다. */
    @Query("SELECT c.publicId AS claimPublicId, c.type AS type, c.status AS status, o.orderNo AS orderNo, "
            + "c.requestedAt AS requestedAt "
            + "FROM Claim c, OrderItem oi JOIN oi.order o WHERE c.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "ORDER BY c.requestedAt DESC, c.id DESC")
    List<DashboardRecentClaimProjection> findRecentClaims(@Param("sellerId") Long sellerId, Pageable pageable);

    /** 기간 결제완료 주문의 자기 상품별 품목 매출 합 상위 N. 상품명은 주문 시점 스냅샷. */
    @Query("SELECT oi.productId AS productId, MAX(oi.productName) AS productName, "
            + "COALESCE(SUM(oi.totalPrice), 0) AS revenue, COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o WHERE oi.sellerId = :sellerId AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY oi.productId ORDER BY SUM(oi.totalPrice) DESC, oi.productId ASC")
    List<DashboardTopProductProjection> findTopProducts(@Param("sellerId") Long sellerId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to, Pageable pageable);
}

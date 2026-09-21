package com.zslab.mall.stats.repository;

import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 셀러 통계 전용 집계 리포지토리(Track 90-E-1·D-200·{@code SellerDashboardRepository} 선례). 관리자 {@link AdminSalesStatsRepository}는
 * {@code Order} 루트라 {@code order.total_price}(타 셀러 몫·배송비·할인 포함)를 합산하므로 sellerId 인자를 주입하지 않고 신설한다 —
 * 셀러 매출은 <b>자기 품목 {@code order_item.total_price} 합</b>(D-192)이며 루트가 {@link OrderItem}이다. 상품 축은 관리자
 * {@link AdminSalesStatsRepository#aggregateByProductInSeller}를 그대로 재사용한다(이미 order_item.seller_id 필터).
 *
 * <p>모든 쿼리는 {@code oi.sellerId = :sellerId}(인덱스 ix_order_item_seller_status)와 paid_at(환불은 refunded_at) 반구간
 * {@code >= from AND < to}를 필수로 가진다. 미결제·만료 주문은 paid_at이 항상 null이라 paid_at 조건만으로 제외된다.
 * 네이티브 쿼리를 쓰지 않으며 모든 변수는 :바인딩만 사용해 SQL injection 위험이 없다.
 */
public interface SellerStatsRepository extends Repository<OrderItem, Long> {

    /** 기간 결제완료 주문의 자기 품목 매출 합·주문 수(DISTINCT order)·수량 합. */
    @Query("SELECT COUNT(DISTINCT o.id) AS orderCount, COALESCE(SUM(oi.totalPrice), 0) AS revenue, "
            + "COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o "
            + "WHERE oi.sellerId = :sellerId AND o.paidAt >= :from AND o.paidAt < :to")
    SellerSalesTotalsProjection sumSales(@Param("sellerId") Long sellerId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 기간 완료 환불액 합(refunded_at 기준). Refund→Claim→OrderItem은 Long 참조라 theta-join 2단으로 자기 품목 클레임의 환불만 잡는다. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r, Claim c, OrderItem oi "
            + "WHERE r.claimId = c.id AND c.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "AND r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to")
    long sumRefund(@Param("sellerId") Long sellerId, @Param("status") RefundStatus status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 결제완료 주문 수(DISTINCT)·자기 품목 매출 합. pattern은 {@code StatsBuckets} 상수(일·주 ISO·월)만 전달한다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', o.paidAt, :pattern) AS bucket, COUNT(DISTINCT o.id) AS orderCount, "
            + "COALESCE(SUM(oi.totalPrice), 0) AS amount "
            + "FROM OrderItem oi JOIN oi.order o "
            + "WHERE oi.sellerId = :sellerId AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern) "
            + "ORDER BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern)")
    List<SalesBucketProjection> sumSalesByBucket(@Param("sellerId") Long sellerId, @Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 완료 환불 건수·amount 합(refunded_at 기준·자기 품목 클레임만). pattern 규약은 {@link #sumSalesByBucket}와 같다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', r.refundedAt, :pattern) AS bucket, COUNT(r) AS orderCount, "
            + "COALESCE(SUM(r.amount), 0) AS amount "
            + "FROM Refund r, Claim c, OrderItem oi "
            + "WHERE r.claimId = c.id AND c.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "AND r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', r.refundedAt, :pattern) "
            + "ORDER BY FUNCTION('DATE_FORMAT', r.refundedAt, :pattern)")
    List<SalesBucketProjection> sumRefundByBucket(@Param("sellerId") Long sellerId, @Param("pattern") String pattern,
            @Param("status") RefundStatus status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 옵션(variant)별 자기 품목 매출 합(매출 내림차순·동률 variant id 오름차순). 이름은 주문 시점 스냅샷(product_name·option_label)이라
     * 이후 옵션 수정·삭제에 과거 집계 표기가 흔들리지 않는다.
     */
    @Query("SELECT oi.variantId AS keyId, MAX(oi.productName) AS productName, MAX(oi.optionLabel) AS optionLabel, "
            + "COALESCE(SUM(oi.totalPrice), 0) AS revenue, COUNT(DISTINCT o.id) AS orderCount, "
            + "COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o WHERE oi.sellerId = :sellerId "
            + "AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY oi.variantId ORDER BY SUM(oi.totalPrice) DESC, oi.variantId ASC")
    List<SellerSalesOptionProjection> aggregateByOption(@Param("sellerId") Long sellerId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 카테고리별 자기 품목 매출 합(매출 내림차순·동률 id 오름차순). order_item에 카테고리 스냅샷이 없어 product.category_id(현행)를
     * theta-join으로 경유한다 — 상품 카테고리 변경 시 과거 주문의 귀속도 옮겨진다(관리자 D-181과 동일). name 별칭은 없으며 서비스가 enrich한다.
     */
    @Query("SELECT p.categoryId AS keyId, COALESCE(SUM(oi.totalPrice), 0) AS revenue, "
            + "COUNT(DISTINCT o.id) AS orderCount, COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o, Product p WHERE oi.productId = p.id AND oi.sellerId = :sellerId "
            + "AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY p.categoryId ORDER BY SUM(oi.totalPrice) DESC, p.categoryId ASC")
    List<SalesAxisProjection> aggregateByCategory(@Param("sellerId") Long sellerId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

package com.zslab.mall.stats.repository;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 관리자 매출 통계 전용 집계 리포지토리(Track 87·D-181·{@code AdminDashboardRepository} 선례). CRUD를 노출하지 않는 {@link Repository}
 * 마커만 상속하며 JPQL은 Order·OrderItem·Refund·Product를 대상으로 한다.
 *
 * <p>모든 쿼리는 paid_at(환불은 refunded_at) 반구간 {@code >= from AND < to} 조건을 필수로 가져 전체 스캔이 없다(order.paid_at은 V31
 * 인덱스). 저장값은 KST 벽시계(JVM TZ·hibernate.jdbc.time_zone Asia/Seoul)라 DATE_FORMAT 구간 문자열도 KST 날짜다.
 * 매출 정의는 D-180과 같다 — 품목 상태(취소·반품)로 제외하지 않고 환불(COMPLETED)로 상쇄한다. 축별 환불 분해는 하지 않는다
 * (refund→claim→order_item theta-join 3단 비용 대비 요약 refund로 충분·D-181).
 * 네이티브 쿼리를 쓰지 않으며 모든 변수는 :바인딩만 사용해 SQL injection 위험이 없다.
 */
public interface AdminSalesStatsRepository extends Repository<Order, Long> {

    /** 기간 결제완료 주문 건수·total_price 합. */
    @Query("SELECT COUNT(o) AS orderCount, COALESCE(SUM(o.totalPrice), 0) AS revenue "
            + "FROM Order o WHERE o.paidAt >= :from AND o.paidAt < :to")
    SalesTotalsProjection sumSales(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 결제완료 주문의 품목 수량 합(주문당 품목수 산출용·수량 기준). */
    @Query("SELECT COALESCE(SUM(oi.quantity), 0) FROM OrderItem oi JOIN oi.order o "
            + "WHERE o.paidAt >= :from AND o.paidAt < :to")
    long sumItemQuantity(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 완료 환불액 합(refunded_at 기준). status는 enum 바인딩(ordinal 비교 함정 회피). */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r "
            + "WHERE r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to")
    long sumRefund(@Param("status") RefundStatus status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 결제완료 주문 건수·total_price 합. pattern은 일 "%Y-%m-%d"·주 "%x-%v"(ISO)·월 "%Y-%m" 서비스 상수만 전달한다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', o.paidAt, :pattern) AS bucket, COUNT(o) AS orderCount, "
            + "COALESCE(SUM(o.totalPrice), 0) AS amount "
            + "FROM Order o WHERE o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern) "
            + "ORDER BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern)")
    List<SalesBucketProjection> sumSalesByBucket(@Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 완료 환불 건수·amount 합(refunded_at 기준). pattern 규약은 {@link #sumSalesByBucket}와 같다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', r.refundedAt, :pattern) AS bucket, COUNT(r) AS orderCount, "
            + "COALESCE(SUM(r.amount), 0) AS amount "
            + "FROM Refund r WHERE r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', r.refundedAt, :pattern) "
            + "ORDER BY FUNCTION('DATE_FORMAT', r.refundedAt, :pattern)")
    List<SalesBucketProjection> sumRefundByBucket(@Param("pattern") String pattern,
            @Param("status") RefundStatus status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 카테고리별 품목 매출 합(매출 내림차순·동률 id 오름차순). order_item에 카테고리 스냅샷이 없어 product.category_id(현행)를
     * theta-join으로 경유한다 — 관리자가 상품 카테고리를 바꾸면 과거 주문의 귀속도 새 카테고리로 옮겨진다(V32 스냅샷 이월·D-181).
     * 주문 이력 있는 상품은 삭제가 차단되므로 Product @SQLRestriction으로 누락되는 품목은 없다. name 별칭은 없으며 서비스가 enrich한다.
     */
    @Query("SELECT p.categoryId AS keyId, COALESCE(SUM(oi.totalPrice), 0) AS revenue, "
            + "COUNT(DISTINCT o.id) AS orderCount, COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o, Product p WHERE oi.productId = p.id "
            + "AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY p.categoryId ORDER BY SUM(oi.totalPrice) DESC, p.categoryId ASC")
    List<SalesAxisProjection> aggregateByCategory(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 셀러별 품목 매출 합(order_item.seller_id 스냅샷·매출 내림차순). name 별칭은 없으며 서비스가 SellerRepository로 enrich한다. */
    @Query("SELECT oi.sellerId AS keyId, COALESCE(SUM(oi.totalPrice), 0) AS revenue, "
            + "COUNT(DISTINCT o.id) AS orderCount, COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o WHERE o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY oi.sellerId ORDER BY SUM(oi.totalPrice) DESC, oi.sellerId ASC")
    List<SalesAxisProjection> aggregateBySeller(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 상품별 품목 매출 합(order_item.product_id·product_name 스냅샷·옵션 단위 미분해·매출 내림차순). */
    @Query("SELECT oi.productId AS keyId, MAX(oi.productName) AS name, COALESCE(SUM(oi.totalPrice), 0) AS revenue, "
            + "COUNT(DISTINCT o.id) AS orderCount, COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o WHERE o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY oi.productId ORDER BY SUM(oi.totalPrice) DESC, oi.productId ASC")
    List<SalesAxisProjection> aggregateByProduct(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 한 셀러의 상품별 품목 매출 합(SELLER 드릴다운·order_item.seller_id 스냅샷 필터). */
    @Query("SELECT oi.productId AS keyId, MAX(oi.productName) AS name, COALESCE(SUM(oi.totalPrice), 0) AS revenue, "
            + "COUNT(DISTINCT o.id) AS orderCount, COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o WHERE oi.sellerId = :sellerId "
            + "AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY oi.productId ORDER BY SUM(oi.totalPrice) DESC, oi.productId ASC")
    List<SalesAxisProjection> aggregateByProductInSeller(@Param("sellerId") Long sellerId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 한 카테고리의 상품별 품목 매출 합(CATEGORY 드릴다운). 귀속 경유·변동 근거는 {@link #aggregateByCategory}와 같다. */
    @Query("SELECT oi.productId AS keyId, MAX(oi.productName) AS name, COALESCE(SUM(oi.totalPrice), 0) AS revenue, "
            + "COUNT(DISTINCT o.id) AS orderCount, COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o, Product p WHERE oi.productId = p.id AND p.categoryId = :categoryId "
            + "AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY oi.productId ORDER BY SUM(oi.totalPrice) DESC, oi.productId ASC")
    List<SalesAxisProjection> aggregateByProductInCategory(@Param("categoryId") Long categoryId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

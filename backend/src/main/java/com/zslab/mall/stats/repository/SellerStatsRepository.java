package com.zslab.mall.stats.repository;

import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 셀러 통계 전용 집계 리포지토리(Track 90-E-1 매출 · 90-E-2 주문·클레임·D-200·{@code SellerDashboardRepository} 선례). 관리자 {@link AdminSalesStatsRepository}는
 * {@code Order} 루트라 {@code order.total_price}(타 셀러 몫·배송비·할인 포함)를 합산하므로 sellerId 인자를 주입하지 않고 신설한다 —
 * 셀러 매출은 <b>자기 품목 {@code order_item.total_price} 합</b>(D-192)이며 루트가 {@link OrderItem}이다. 상품 축은 관리자
 * {@link AdminSalesStatsRepository#aggregateByProductInSeller}를 그대로 재사용한다(이미 order_item.seller_id 필터).
 *
 * <p>모든 쿼리는 {@code oi.sellerId = :sellerId}(인덱스 ix_order_item_seller_status)와 paid_at(환불은 refunded_at·클레임은 requested_at·
 * 소요시간은 종결 시각) 반구간 {@code >= from AND < to}를 필수로 가진다. 주문·클레임 지표는 관리자 {@link AdminOrderStatsRepository}
 * 정의를 품목 단위로 좁힌 것이며(D-200 90-E-2 정의표) Claim·Delivery·Refund는 Long 참조라 OrderItem과 theta-join한다.
 * 원 발송 delivery는 {@code direction = OUTBOUND AND claimId IS NULL}(반품 회수·교환품/재발송 제외). 미결제·만료 주문은 paid_at이 항상 null이라 paid_at 조건만으로 제외된다.
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

    // ---------- 주문·클레임(90-E-2) ----------

    /**
     * 결제 코호트 퍼널(품목 단위): 기간 내 paid_at 주문의 자기 품목을 코호트로 잡고 원 발송 shipped_at·delivered_at 도달을 센다(도달 시각이 기간 밖이어도
     * 도달). Delivery가 품목 1:1이라 혼합 주문의 부분 출고는 품목별로 표현된다.
     */
    @Query("SELECT COUNT(oi) AS paidItems, "
            + "COALESCE(SUM(CASE WHEN d.shippedAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS shippedItems, "
            + "COALESCE(SUM(CASE WHEN d.deliveredAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS deliveredItems "
            + "FROM OrderItem oi JOIN oi.order o "
            + "LEFT JOIN Delivery d ON d.orderItemId = oi.id AND d.direction = :outbound AND d.claimId IS NULL "
            + "WHERE oi.sellerId = :sellerId AND o.paidAt >= :from AND o.paidAt < :to")
    SellerOrderFunnelProjection aggregateFunnel(@Param("sellerId") Long sellerId, @Param("outbound") DeliveryDirection outbound,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 결제→발송 시각 쌍(자기 품목·원 발송·shipped_at이 기간에 속하는 건). */
    @Query("SELECT o.paidAt AS startAt, d.shippedAt AS endAt "
            + "FROM Delivery d, OrderItem oi JOIN oi.order o WHERE d.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "AND d.direction = :outbound AND d.claimId IS NULL AND o.paidAt IS NOT NULL "
            + "AND d.shippedAt >= :from AND d.shippedAt < :to")
    List<TimePairProjection> findPaidToShippedPairs(@Param("sellerId") Long sellerId, @Param("outbound") DeliveryDirection outbound,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 발송→배송완료 시각 쌍(자기 품목·원 발송·delivered_at이 기간에 속하는 건). 관리자는 Delivery 단독이라 OrderItem theta-join을 더한다. */
    @Query("SELECT d.shippedAt AS startAt, d.deliveredAt AS endAt FROM Delivery d, OrderItem oi "
            + "WHERE d.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "AND d.direction = :outbound AND d.claimId IS NULL AND d.shippedAt IS NOT NULL "
            + "AND d.deliveredAt >= :from AND d.deliveredAt < :to")
    List<TimePairProjection> findShippedToDeliveredPairs(@Param("sellerId") Long sellerId, @Param("outbound") DeliveryDirection outbound,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 내 요청(requested_at) 자기 품목 클레임 건수(재요청 포함·클레임률 분자). */
    @Query("SELECT COUNT(c) FROM Claim c, OrderItem oi WHERE c.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "AND c.requestedAt >= :from AND c.requestedAt < :to")
    long countClaims(@Param("sellerId") Long sellerId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 결제완료 주문의 자기 품목 라인 수(클레임률 분모). */
    @Query("SELECT COUNT(oi) FROM OrderItem oi JOIN oi.order o WHERE oi.sellerId = :sellerId AND o.paidAt >= :from AND o.paidAt < :to")
    long countPaidItems(@Param("sellerId") Long sellerId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 COMPLETED 환불 건수·금액 합(refunded_at·자기 품목 클레임만·환불률 분자). 분모는 {@link #sumSales}의 자기 품목 매출(D-200 결정 3 α). */
    @Query("SELECT COUNT(r) AS refundCount, COALESCE(SUM(r.amount), 0) AS refundAmount FROM Refund r, Claim c, OrderItem oi "
            + "WHERE r.claimId = c.id AND c.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "AND r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to")
    RefundTotalsProjection sumRefundTotals(@Param("sellerId") Long sellerId, @Param("status") RefundStatus status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 자기 품목 클레임 요청 건수(requested_at). pattern은 {@code StatsBuckets} 상수만 전달한다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', c.requestedAt, :pattern) AS bucket, COUNT(c) AS bucketCount "
            + "FROM Claim c, OrderItem oi WHERE c.orderItemId = oi.id AND oi.sellerId = :sellerId "
            + "AND c.requestedAt >= :from AND c.requestedAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', c.requestedAt, :pattern)")
    List<StatsCountBucketProjection> countClaimsByBucket(@Param("sellerId") Long sellerId, @Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 결제 자기 품목 라인 수(paid_at·클레임률 분모). */
    @Query("SELECT FUNCTION('DATE_FORMAT', o.paidAt, :pattern) AS bucket, COUNT(oi) AS bucketCount "
            + "FROM OrderItem oi JOIN oi.order o WHERE oi.sellerId = :sellerId AND o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern)")
    List<StatsCountBucketProjection> countPaidItemsByBucket(@Param("sellerId") Long sellerId, @Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 내 요청 자기 품목 클레임의 유형별 건수(건수 내림차순·동률 유형 순). */
    @Query("SELECT c.type AS claimType, COUNT(c) AS claimCount FROM Claim c, OrderItem oi "
            + "WHERE c.orderItemId = oi.id AND oi.sellerId = :sellerId AND c.requestedAt >= :from AND c.requestedAt < :to "
            + "GROUP BY c.type ORDER BY COUNT(c) DESC, c.type ASC")
    List<ClaimTypeCountProjection> countClaimsByType(@Param("sellerId") Long sellerId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 내 요청 자기 품목 클레임의 사유 코드별 건수(건수 내림차순·동률 코드 순). reason_code는 무제약 VARCHAR라 원문 그대로. */
    @Query("SELECT c.reasonCode AS reasonCode, COUNT(c) AS claimCount FROM Claim c, OrderItem oi "
            + "WHERE c.orderItemId = oi.id AND oi.sellerId = :sellerId AND c.requestedAt >= :from AND c.requestedAt < :to "
            + "GROUP BY c.reasonCode ORDER BY COUNT(c) DESC, c.reasonCode ASC")
    List<ClaimReasonCountProjection> countClaimsByReason(@Param("sellerId") Long sellerId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 내 요청 자기 품목 클레임의 상품별 건수(건수 내림차순·동률 상품 id 순·이름은 order_item.product_name 스냅샷). */
    @Query("SELECT oi.productId AS keyId, MAX(oi.productName) AS productName, COUNT(c) AS claimCount FROM Claim c, OrderItem oi "
            + "WHERE c.orderItemId = oi.id AND oi.sellerId = :sellerId AND c.requestedAt >= :from AND c.requestedAt < :to "
            + "GROUP BY oi.productId ORDER BY COUNT(c) DESC, oi.productId ASC")
    List<SellerClaimProductProjection> countClaimsByProduct(@Param("sellerId") Long sellerId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

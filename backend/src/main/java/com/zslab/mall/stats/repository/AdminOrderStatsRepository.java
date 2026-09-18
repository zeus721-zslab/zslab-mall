package com.zslab.mall.stats.repository;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 관리자 주문·클레임 통계 전용 집계 리포지토리(Track 88·D-182·{@code AdminSalesStatsRepository} 선례). CRUD를 노출하지 않는 {@link Repository}
 * 마커만 상속하며 JPQL은 Order·OrderItem·Delivery·Claim·Refund를 대상으로 한다. 매출 통계 리포지토리는 수정하지 않고(회귀 방지)
 * 같은 정의(D-180 매출·환불)의 분모 쿼리를 여기에 따로 둔다.
 *
 * <p>원 발송 delivery는 {@code direction = OUTBOUND AND claimId IS NULL}(반품 회수·교환품/재발송 제외)로 잡는다. 소요시간 표본은 종결 시각(뒤 시각)이
 * 기간에 속하는 건이며 시각 쌍만 내려 평균·중앙값은 서비스가 계산한다(네이티브·윈도우 함수 0 관례 유지).
 * 네이티브 쿼리를 쓰지 않으며 모든 변수는 :바인딩만 사용해 SQL injection 위험이 없다.
 */
public interface AdminOrderStatsRepository extends Repository<Order, Long> {

    /**
     * 결제 코호트 퍼널: 기간 내 paid_at 주문의 품목을 코호트로 잡고 원 발송 shipped_at·delivered_at·confirmed_at 도달과 취소·반품 종결을 한 번에 센다.
     * 도달 시각이 기간 밖이어도 도달로 집계한다(코호트 정의).
     */
    @Query("SELECT COUNT(oi) AS paidItems, "
            + "COALESCE(SUM(CASE WHEN d.shippedAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS shippedItems, "
            + "COALESCE(SUM(CASE WHEN d.deliveredAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS deliveredItems, "
            + "COALESCE(SUM(CASE WHEN oi.confirmedAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS confirmedItems, "
            + "COALESCE(SUM(CASE WHEN oi.itemStatus = :cancelled THEN 1 ELSE 0 END), 0) AS cancelledItems, "
            + "COALESCE(SUM(CASE WHEN oi.itemStatus = :returned THEN 1 ELSE 0 END), 0) AS returnedItems "
            + "FROM OrderItem oi JOIN oi.order o "
            + "LEFT JOIN Delivery d ON d.orderItemId = oi.id AND d.direction = :outbound AND d.claimId IS NULL "
            + "WHERE o.paidAt >= :from AND o.paidAt < :to")
    OrderFunnelProjection aggregateFunnel(@Param("outbound") DeliveryDirection outbound,
            @Param("cancelled") OrderItemStatus cancelled, @Param("returned") OrderItemStatus returned,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 결제→발송 시각 쌍(원 발송·shipped_at이 기간에 속하는 건). */
    @Query("SELECT o.paidAt AS startAt, d.shippedAt AS endAt "
            + "FROM Delivery d, OrderItem oi JOIN oi.order o WHERE d.orderItemId = oi.id "
            + "AND d.direction = :outbound AND d.claimId IS NULL AND o.paidAt IS NOT NULL "
            + "AND d.shippedAt >= :from AND d.shippedAt < :to")
    List<TimePairProjection> findPaidToShippedPairs(@Param("outbound") DeliveryDirection outbound,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 발송→배송완료 시각 쌍(원 발송·delivered_at이 기간에 속하는 건). */
    @Query("SELECT d.shippedAt AS startAt, d.deliveredAt AS endAt FROM Delivery d "
            + "WHERE d.direction = :outbound AND d.claimId IS NULL AND d.shippedAt IS NOT NULL "
            + "AND d.deliveredAt >= :from AND d.deliveredAt < :to")
    List<TimePairProjection> findShippedToDeliveredPairs(@Param("outbound") DeliveryDirection outbound,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 클레임 요청→종결 시각 쌍(status COMPLETED·REJECTED·processed_at이 기간에 속하는 건). */
    @Query("SELECT c.requestedAt AS startAt, c.processedAt AS endAt FROM Claim c "
            + "WHERE c.status IN :closedStatuses AND c.requestedAt IS NOT NULL "
            + "AND c.processedAt >= :from AND c.processedAt < :to")
    List<TimePairProjection> findClaimRequestedToClosedPairs(@Param("closedStatuses") Collection<ClaimStatus> closedStatuses,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 내 요청(requested_at) 클레임 건수(재요청 포함). */
    @Query("SELECT COUNT(c) FROM Claim c WHERE c.requestedAt >= :from AND c.requestedAt < :to")
    long countClaims(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 결제완료 주문의 품목 라인 수(클레임률 분모). */
    @Query("SELECT COUNT(oi) FROM OrderItem oi JOIN oi.order o WHERE o.paidAt >= :from AND o.paidAt < :to")
    long countPaidItems(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 결제완료 매출(order.total_price 합·환불률 분모·D-180 정의). */
    @Query("SELECT COALESCE(SUM(o.totalPrice), 0) FROM Order o WHERE o.paidAt >= :from AND o.paidAt < :to")
    long sumRevenue(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 COMPLETED 환불 건수·금액 합(refunded_at 기준). */
    @Query("SELECT COUNT(r) AS refundCount, COALESCE(SUM(r.amount), 0) AS refundAmount FROM Refund r "
            + "WHERE r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to")
    RefundTotalsProjection sumRefunds(@Param("status") RefundStatus status, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 구간별 클레임 요청 건수(requested_at). pattern은 {@code StatsBuckets} 상수만 전달한다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', c.requestedAt, :pattern) AS bucket, COUNT(c) AS bucketCount "
            + "FROM Claim c WHERE c.requestedAt >= :from AND c.requestedAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', c.requestedAt, :pattern)")
    List<StatsCountBucketProjection> countClaimsByBucket(@Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 결제 품목 라인 수(paid_at·클레임률 분모). */
    @Query("SELECT FUNCTION('DATE_FORMAT', o.paidAt, :pattern) AS bucket, COUNT(oi) AS bucketCount "
            + "FROM OrderItem oi JOIN oi.order o WHERE o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern)")
    List<StatsCountBucketProjection> countPaidItemsByBucket(@Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 결제완료 주문 건수·매출 합(paid_at·환불률 분모). */
    @Query("SELECT FUNCTION('DATE_FORMAT', o.paidAt, :pattern) AS bucket, COUNT(o) AS bucketCount, "
            + "COALESCE(SUM(o.totalPrice), 0) AS amount FROM Order o WHERE o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern)")
    List<StatsBucketProjection> sumRevenueByBucket(@Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 COMPLETED 환불 건수·금액 합(refunded_at). */
    @Query("SELECT FUNCTION('DATE_FORMAT', r.refundedAt, :pattern) AS bucket, COUNT(r) AS bucketCount, "
            + "COALESCE(SUM(r.amount), 0) AS amount FROM Refund r "
            + "WHERE r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', r.refundedAt, :pattern)")
    List<StatsBucketProjection> sumRefundsByBucket(@Param("pattern") String pattern, @Param("status") RefundStatus status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 내 요청 클레임의 유형별 건수(건수 내림차순·동률 유형 순). */
    @Query("SELECT c.type AS claimType, COUNT(c) AS claimCount FROM Claim c "
            + "WHERE c.requestedAt >= :from AND c.requestedAt < :to GROUP BY c.type ORDER BY COUNT(c) DESC, c.type ASC")
    List<ClaimTypeCountProjection> countClaimsByType(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 내 요청 클레임의 사유 코드별 건수(건수 내림차순·동률 코드 순). reason_code는 무제약 VARCHAR라 원문 그대로. */
    @Query("SELECT c.reasonCode AS reasonCode, COUNT(c) AS claimCount FROM Claim c "
            + "WHERE c.requestedAt >= :from AND c.requestedAt < :to GROUP BY c.reasonCode ORDER BY COUNT(c) DESC, c.reasonCode ASC")
    List<ClaimReasonCountProjection> countClaimsByReason(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

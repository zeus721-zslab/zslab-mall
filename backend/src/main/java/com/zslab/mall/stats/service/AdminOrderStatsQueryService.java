package com.zslab.mall.stats.service;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.stats.controller.response.AdminOrderStatsResponse;
import com.zslab.mall.stats.controller.response.ClaimReasonShareResponse;
import com.zslab.mall.stats.controller.response.ClaimSummaryResponse;
import com.zslab.mall.stats.controller.response.ClaimTrendBucketResponse;
import com.zslab.mall.stats.controller.response.ClaimTypeShareResponse;
import com.zslab.mall.stats.controller.response.OrderFunnelResponse;
import com.zslab.mall.stats.controller.response.OrderLeadTimeResponse;
import com.zslab.mall.stats.controller.response.StatsRatio;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.repository.AdminOrderStatsRepository;
import com.zslab.mall.stats.repository.OrderFunnelProjection;
import com.zslab.mall.stats.repository.RefundTotalsProjection;
import com.zslab.mall.stats.repository.StatsBucketProjection;
import com.zslab.mall.stats.repository.StatsCountBucketProjection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 주문·클레임 통계 조회(Track 88·D-182·{@code AdminSalesStatsQueryService} 패턴). 기간·비교 기간({@link StatsPeriod})·구간 키
 * ({@link StatsBuckets})는 매출 통계와 공유하고 집계는 {@link AdminOrderStatsRepository}가, 소요시간 통계는 {@link LeadTimeCalculator}가 맡는다.
 *
 * <p>비교 기간은 클레임 요약·추이에만 적용한다(퍼널·소요시간·분포는 비교 없음). 비교 기간에 클레임·환불·결제 품목이 모두 0건이면
 * compareClaimSummary·compareClaimTrend는 null(D-181 규약).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminOrderStatsQueryService {

    private static final List<ClaimStatus> CLOSED_CLAIM_STATUSES = List.of(ClaimStatus.COMPLETED, ClaimStatus.REJECTED);

    private final AdminOrderStatsRepository statsRepository;

    /**
     * 퍼널 + 소요시간 + 클레임 요약·추이·분포.
     *
     * @throws MalformedRequestException from &gt; to(400)
     */
    public AdminOrderStatsResponse getOrderStats(LocalDate from, LocalDate to, StatsUnit unit, StatsCompare compare) {
        StatsPeriod period = StatsPeriod.of(from, to);
        List<StatsBuckets.Bucket> buckets = StatsBuckets.of(period.from(), unit, StatsBuckets.count(period, unit));

        OrderFunnelResponse funnel = funnel(period);
        OrderLeadTimeResponse leadTime = leadTime(period);
        ClaimSummaryResponse claimSummary = claimSummary(period);
        List<ClaimTrendBucketResponse> claimTrend = claimTrend(period, unit, buckets);
        long totalClaims = claimSummary.claimCount();
        List<ClaimTypeShareResponse> byType = statsRepository.countClaimsByType(period.start(), period.end()).stream()
                .map(row -> new ClaimTypeShareResponse(row.getClaimType(), row.getClaimCount(),
                        StatsRatio.percent(row.getClaimCount(), totalClaims)))
                .toList();
        List<ClaimReasonShareResponse> byReason = statsRepository.countClaimsByReason(period.start(), period.end()).stream()
                .map(row -> new ClaimReasonShareResponse(row.getReasonCode(), row.getClaimCount(),
                        StatsRatio.percent(row.getClaimCount(), totalClaims)))
                .toList();

        StatsPeriod comparePeriod = period.compareWith(compare);
        ClaimSummaryResponse compareSummary = comparePeriod == null ? null : claimSummary(comparePeriod);
        if (compareSummary == null || compareSummary.hasNoData()) {
            return new AdminOrderStatsResponse(funnel, leadTime, claimSummary, null, claimTrend, null, byType, byReason);
        }
        List<StatsBuckets.Bucket> compareBuckets = StatsBuckets.of(comparePeriod.from(), unit, buckets.size());
        return new AdminOrderStatsResponse(funnel, leadTime, claimSummary, compareSummary, claimTrend,
                claimTrend(comparePeriod, unit, compareBuckets), byType, byReason);
    }

    private OrderFunnelResponse funnel(StatsPeriod period) {
        OrderFunnelProjection row = statsRepository.aggregateFunnel(DeliveryDirection.OUTBOUND, OrderItemStatus.CANCELLED,
                OrderItemStatus.RETURNED, period.start(), period.end());
        return new OrderFunnelResponse(row.getPaidItems(), row.getShippedItems(), row.getDeliveredItems(),
                row.getConfirmedItems(), row.getCancelledItems(), row.getReturnedItems());
    }

    private OrderLeadTimeResponse leadTime(StatsPeriod period) {
        return new OrderLeadTimeResponse(
                LeadTimeCalculator.calculate("paidToShipped",
                        statsRepository.findPaidToShippedPairs(DeliveryDirection.OUTBOUND, period.start(), period.end())),
                LeadTimeCalculator.calculate("shippedToDelivered",
                        statsRepository.findShippedToDeliveredPairs(DeliveryDirection.OUTBOUND, period.start(), period.end())),
                LeadTimeCalculator.calculate("claimRequestedToClosed",
                        statsRepository.findClaimRequestedToClosedPairs(CLOSED_CLAIM_STATUSES, period.start(), period.end())));
    }

    private ClaimSummaryResponse claimSummary(StatsPeriod period) {
        long claimCount = statsRepository.countClaims(period.start(), period.end());
        long paidItemCount = statsRepository.countPaidItems(period.start(), period.end());
        long revenue = statsRepository.sumRevenue(period.start(), period.end());
        RefundTotalsProjection refunds = statsRepository.sumRefunds(RefundStatus.COMPLETED, period.start(), period.end());
        return ClaimSummaryResponse.of(claimCount, paidItemCount, refunds.getRefundAmount(), refunds.getRefundCount(), revenue);
    }

    /** 클레임은 requested_at·분모(결제 품목·매출)는 paid_at·환불은 refunded_at 구간이라 각각 집계 후 dbKey로 합친다. 구간에 없는 키는 0. */
    private List<ClaimTrendBucketResponse> claimTrend(StatsPeriod period, StatsUnit unit, List<StatsBuckets.Bucket> buckets) {
        String pattern = StatsBuckets.pattern(unit);
        Map<String, Long> claims = countByBucket(statsRepository.countClaimsByBucket(pattern, period.start(), period.end()));
        Map<String, Long> paidItems = countByBucket(
                statsRepository.countPaidItemsByBucket(pattern, period.start(), period.end()));
        Map<String, StatsBucketProjection> revenue = byBucket(
                statsRepository.sumRevenueByBucket(pattern, period.start(), period.end()));
        Map<String, StatsBucketProjection> refunds = byBucket(
                statsRepository.sumRefundsByBucket(pattern, RefundStatus.COMPLETED, period.start(), period.end()));
        List<ClaimTrendBucketResponse> rows = new ArrayList<>(buckets.size());
        for (StatsBuckets.Bucket bucket : buckets) {
            StatsBucketProjection refund = refunds.get(bucket.dbKey());
            StatsBucketProjection sale = revenue.get(bucket.dbKey());
            rows.add(ClaimTrendBucketResponse.of(bucket.key(), bucket.label(),
                    claims.getOrDefault(bucket.dbKey(), 0L), paidItems.getOrDefault(bucket.dbKey(), 0L),
                    amountOf(refund), countOf(refund), amountOf(sale)));
        }
        return rows;
    }

    private static Map<String, Long> countByBucket(List<StatsCountBucketProjection> rows) {
        return rows.stream().collect(Collectors.toMap(StatsCountBucketProjection::getBucket,
                StatsCountBucketProjection::getBucketCount));
    }

    private static Map<String, StatsBucketProjection> byBucket(List<StatsBucketProjection> rows) {
        return rows.stream().collect(Collectors.toMap(StatsBucketProjection::getBucket, Function.identity()));
    }

    private static long amountOf(StatsBucketProjection row) {
        return row == null ? 0L : row.getAmount();
    }

    private static long countOf(StatsBucketProjection row) {
        return row == null ? 0L : row.getBucketCount();
    }
}

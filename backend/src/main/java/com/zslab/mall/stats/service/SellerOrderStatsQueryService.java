package com.zslab.mall.stats.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.stats.controller.response.ClaimReasonShareResponse;
import com.zslab.mall.stats.controller.response.ClaimSummaryResponse;
import com.zslab.mall.stats.controller.response.ClaimTrendBucketResponse;
import com.zslab.mall.stats.controller.response.ClaimTypeShareResponse;
import com.zslab.mall.stats.controller.response.SellerClaimProductShareResponse;
import com.zslab.mall.stats.controller.response.SellerOrderFunnelResponse;
import com.zslab.mall.stats.controller.response.SellerOrderLeadTimeResponse;
import com.zslab.mall.stats.controller.response.SellerOrderStatsResponse;
import com.zslab.mall.stats.controller.response.StatsRatio;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.repository.RefundTotalsProjection;
import com.zslab.mall.stats.repository.SalesBucketProjection;
import com.zslab.mall.stats.repository.SellerClaimProductProjection;
import com.zslab.mall.stats.repository.SellerOrderFunnelProjection;
import com.zslab.mall.stats.repository.SellerStatsRepository;
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
 * 셀러 주문·클레임 통계 조회(Track 90-E-2·D-200·{@code AdminOrderStatsQueryService} 흐름 복제). 기간·비교 기간({@link StatsPeriod})·구간 키
 * ({@link StatsBuckets})·소요시간 계산({@link LeadTimeCalculator})은 관리자와 공유하고 집계는 전부 {@link SellerStatsRepository}(order_item 축)다.
 * 환불률 분모는 자기 품목 매출({@code sumSales}·D-200 결정 3 α)이며 관리자의 order.total_price를 쓰지 않는다.
 *
 * <p>기간은 최대 {@value #MAX_PERIOD_DAYS}일(90-E-1과 동일). 비교 기간은 클레임 요약·추이에만 적용하고 비교 기간에 클레임·환불·결제 품목이
 * 모두 0건이면 compareClaimSummary·compareClaimTrend는 null(D-181 규약).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerOrderStatsQueryService {

    static final int MAX_PERIOD_DAYS = SellerSalesStatsQueryService.MAX_PERIOD_DAYS;

    private final SellerStatsRepository sellerStatsRepository;
    private final ProductRepository productRepository;

    /**
     * 퍼널 + 소요시간 + 클레임 요약·추이·분포·상품별 분해.
     *
     * @throws MalformedRequestException from &gt; to 또는 기간이 {@value #MAX_PERIOD_DAYS}일 초과(400)
     */
    public SellerOrderStatsResponse getOrderStats(Long sellerId, LocalDate from, LocalDate to, StatsUnit unit, StatsCompare compare) {
        StatsPeriod period = period(from, to);
        List<StatsBuckets.Bucket> buckets = StatsBuckets.of(period.from(), unit, StatsBuckets.count(period, unit));

        SellerOrderFunnelResponse funnel = funnel(sellerId, period);
        SellerOrderLeadTimeResponse leadTime = leadTime(sellerId, period);
        ClaimSummaryResponse claimSummary = claimSummary(sellerId, period);
        List<ClaimTrendBucketResponse> claimTrend = claimTrend(sellerId, period, unit, buckets);
        long totalClaims = claimSummary.claimCount();
        List<ClaimTypeShareResponse> byType = sellerStatsRepository.countClaimsByType(sellerId, period.start(), period.end()).stream()
                .map(row -> new ClaimTypeShareResponse(row.getClaimType(), row.getClaimCount(),
                        StatsRatio.percent(row.getClaimCount(), totalClaims)))
                .toList();
        List<ClaimReasonShareResponse> byReason = sellerStatsRepository.countClaimsByReason(sellerId, period.start(), period.end()).stream()
                .map(row -> new ClaimReasonShareResponse(row.getReasonCode(), row.getClaimCount(),
                        StatsRatio.percent(row.getClaimCount(), totalClaims)))
                .toList();
        List<SellerClaimProductShareResponse> byProduct = claimByProduct(sellerId, period, totalClaims);

        StatsPeriod comparePeriod = period.compareWith(compare);
        ClaimSummaryResponse compareSummary = comparePeriod == null ? null : claimSummary(sellerId, comparePeriod);
        if (compareSummary == null || compareSummary.hasNoData()) {
            return new SellerOrderStatsResponse(funnel, leadTime, claimSummary, null, claimTrend, null, byType, byReason, byProduct);
        }
        List<StatsBuckets.Bucket> compareBuckets = StatsBuckets.of(comparePeriod.from(), unit, buckets.size());
        return new SellerOrderStatsResponse(funnel, leadTime, claimSummary, compareSummary, claimTrend,
                claimTrend(sellerId, comparePeriod, unit, compareBuckets), byType, byReason, byProduct);
    }

    /**
     * @throws MalformedRequestException from &gt; to(StatsPeriod) 또는 기간 초과
     */
    private static StatsPeriod period(LocalDate from, LocalDate to) {
        StatsPeriod period = StatsPeriod.of(from, to);
        if (period.dayCount() > MAX_PERIOD_DAYS) {
            throw new MalformedRequestException("기간은 최대 " + MAX_PERIOD_DAYS + "일입니다.");
        }
        return period;
    }

    private SellerOrderFunnelResponse funnel(Long sellerId, StatsPeriod period) {
        SellerOrderFunnelProjection row = sellerStatsRepository.aggregateFunnel(sellerId, DeliveryDirection.OUTBOUND,
                period.start(), period.end());
        return new SellerOrderFunnelResponse(row.getPaidItems(), row.getShippedItems(), row.getDeliveredItems());
    }

    private SellerOrderLeadTimeResponse leadTime(Long sellerId, StatsPeriod period) {
        return new SellerOrderLeadTimeResponse(
                LeadTimeCalculator.calculate("paidToShipped",
                        sellerStatsRepository.findPaidToShippedPairs(sellerId, DeliveryDirection.OUTBOUND, period.start(), period.end())),
                LeadTimeCalculator.calculate("shippedToDelivered",
                        sellerStatsRepository.findShippedToDeliveredPairs(sellerId, DeliveryDirection.OUTBOUND, period.start(), period.end())));
    }

    private ClaimSummaryResponse claimSummary(Long sellerId, StatsPeriod period) {
        long claimCount = sellerStatsRepository.countClaims(sellerId, period.start(), period.end());
        long paidItemCount = sellerStatsRepository.countPaidItems(sellerId, period.start(), period.end());
        long revenue = sellerStatsRepository.sumSales(sellerId, period.start(), period.end()).getRevenue();
        RefundTotalsProjection refunds = sellerStatsRepository.sumRefundTotals(sellerId, RefundStatus.COMPLETED, period.start(), period.end());
        return ClaimSummaryResponse.of(claimCount, paidItemCount, refunds.getRefundAmount(), refunds.getRefundCount(), revenue);
    }

    /** 클레임은 requested_at·분모(결제 품목·자기 품목 매출)는 paid_at·환불은 refunded_at 구간이라 각각 집계 후 dbKey로 합친다. 구간에 없는 키는 0. */
    private List<ClaimTrendBucketResponse> claimTrend(Long sellerId, StatsPeriod period, StatsUnit unit, List<StatsBuckets.Bucket> buckets) {
        String pattern = StatsBuckets.pattern(unit);
        Map<String, Long> claims = countByBucket(sellerStatsRepository.countClaimsByBucket(sellerId, pattern, period.start(), period.end()));
        Map<String, Long> paidItems = countByBucket(
                sellerStatsRepository.countPaidItemsByBucket(sellerId, pattern, period.start(), period.end()));
        Map<String, SalesBucketProjection> revenue = byBucket(
                sellerStatsRepository.sumSalesByBucket(sellerId, pattern, period.start(), period.end()));
        Map<String, SalesBucketProjection> refunds = byBucket(
                sellerStatsRepository.sumRefundByBucket(sellerId, pattern, RefundStatus.COMPLETED, period.start(), period.end()));
        List<ClaimTrendBucketResponse> rows = new ArrayList<>(buckets.size());
        for (StatsBuckets.Bucket bucket : buckets) {
            SalesBucketProjection refund = refunds.get(bucket.dbKey());
            SalesBucketProjection sale = revenue.get(bucket.dbKey());
            rows.add(ClaimTrendBucketResponse.of(bucket.key(), bucket.label(),
                    claims.getOrDefault(bucket.dbKey(), 0L), paidItems.getOrDefault(bucket.dbKey(), 0L),
                    amountOf(refund), countOf(refund), amountOf(sale)));
        }
        return rows;
    }

    /** 상품별 클레임 건수 + 상품 public_id enrich(soft-delete로 미존재면 null). 이름은 스냅샷이라 enrich하지 않는다. */
    private List<SellerClaimProductShareResponse> claimByProduct(Long sellerId, StatsPeriod period, long totalClaims) {
        List<SellerClaimProductProjection> rows = sellerStatsRepository.countClaimsByProduct(sellerId, period.start(), period.end());
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> publicIds = productRepository.findByIdIn(rows.stream().map(SellerClaimProductProjection::getKeyId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getPublicId));
        return rows.stream()
                .map(row -> new SellerClaimProductShareResponse(publicIds.get(row.getKeyId()), row.getProductName(),
                        row.getClaimCount(), StatsRatio.percent(row.getClaimCount(), totalClaims)))
                .toList();
    }

    private static Map<String, Long> countByBucket(List<StatsCountBucketProjection> rows) {
        return rows.stream().collect(Collectors.toMap(StatsCountBucketProjection::getBucket, StatsCountBucketProjection::getBucketCount));
    }

    private static Map<String, SalesBucketProjection> byBucket(List<SalesBucketProjection> rows) {
        return rows.stream().collect(Collectors.toMap(SalesBucketProjection::getBucket, Function.identity()));
    }

    private static long amountOf(SalesBucketProjection row) {
        return row == null ? 0L : row.getAmount();
    }

    /** 환불 버킷은 {@code orderCount} 별칭에 환불 건수가 실린다(90-E-1 sumRefundByBucket 재사용). */
    private static long countOf(SalesBucketProjection row) {
        return row == null ? 0L : row.getOrderCount();
    }
}

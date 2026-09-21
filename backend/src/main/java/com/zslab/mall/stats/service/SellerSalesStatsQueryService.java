package com.zslab.mall.stats.service;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.stats.controller.response.SalesSummaryResponse;
import com.zslab.mall.stats.controller.response.SalesTrendBucketResponse;
import com.zslab.mall.stats.controller.response.SellerSalesBreakdownResponse;
import com.zslab.mall.stats.controller.response.SellerSalesBreakdownRowResponse;
import com.zslab.mall.stats.controller.response.SellerSalesStatsResponse;
import com.zslab.mall.stats.enums.SellerStatsAxis;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.repository.AdminSalesStatsRepository;
import com.zslab.mall.stats.repository.SalesAxisProjection;
import com.zslab.mall.stats.repository.SalesBucketProjection;
import com.zslab.mall.stats.repository.SellerSalesOptionProjection;
import com.zslab.mall.stats.repository.SellerSalesTotalsProjection;
import com.zslab.mall.stats.repository.SellerStatsRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 매출 통계 조회(Track 90-E-1·D-200·{@code AdminSalesStatsQueryService} 흐름 복제). 셀러 식별·상태 판정은 {@code SellerActorResolver}가
 * 끝냈으므로(sellerId 확정) 기간·비교 기간({@link StatsPeriod})·구간 키({@link StatsBuckets})·빈 구간 0 채움·이름 enrich·응답 조립만 한다.
 * 집계는 {@link SellerStatsRepository}(order_item 축)이며 상품 축만 관리자 {@link AdminSalesStatsRepository#aggregateByProductInSeller}를 재사용한다.
 *
 * <p>기간은 KST 일 양끝 포함·내부 반구간이며 최대 {@value #MAX_PERIOD_DAYS}일(관리자 통계는 상한 없음·셀러 대시보드는 92일 — D-200).
 * 비교 기간은 관리자와 같은 규약(PREVIOUS = 같은 일수 직전·YEAR_AGO = 1년 전 같은 구간·데이터 0건이면 null).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerSalesStatsQueryService {

    static final int MAX_PERIOD_DAYS = 365;
    private static final double SHARE_SCALE = 100.0;
    private static final double PERCENT = 100.0;
    private static final String NAME_SEPARATOR = " / ";
    private static final String NO_OPTION_LABEL = "(옵션 없음)";

    private final SellerStatsRepository sellerStatsRepository;
    private final AdminSalesStatsRepository adminSalesStatsRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    /** 축 행의 집계값(축별 projection을 한 형태로 맞춘 것). name은 스냅샷 이름(PRODUCT·OPTION)·CATEGORY는 null(enrich). */
    private record AxisRow(Long keyId, String name, long revenue, long orderCount, long quantity) {
    }

    /** 축 행의 응답 key·이름(enrich 결과). */
    private record AxisLabel(String key, String name) {
    }

    /**
     * 요약 + 추이. compare가 NONE이거나 비교 기간에 결제 주문·완료 환불이 모두 0건이면 compareSummary·compareTrend는 null이다.
     *
     * @throws MalformedRequestException from &gt; to 또는 기간이 {@value #MAX_PERIOD_DAYS}일 초과(400)
     */
    public SellerSalesStatsResponse getSales(Long sellerId, LocalDate from, LocalDate to, StatsUnit unit, StatsCompare compare) {
        StatsPeriod period = period(from, to);
        List<StatsBuckets.Bucket> buckets = StatsBuckets.of(period.from(), unit, StatsBuckets.count(period, unit));
        SalesSummaryResponse summary = summary(sellerId, period);
        List<SalesTrendBucketResponse> trend = trend(sellerId, period, unit, buckets);

        StatsPeriod comparePeriod = period.compareWith(compare);
        SalesSummaryResponse compareSummary = comparePeriod == null ? null : summary(sellerId, comparePeriod);
        if (compareSummary == null || compareSummary.hasNoData()) {
            return new SellerSalesStatsResponse(summary, null, trend, null);
        }
        List<StatsBuckets.Bucket> compareBuckets = StatsBuckets.of(comparePeriod.from(), unit, buckets.size());
        return new SellerSalesStatsResponse(summary, compareSummary, trend, trend(sellerId, comparePeriod, unit, compareBuckets));
    }

    /**
     * 분해 테이블(상품·옵션·카테고리·매출 내림차순 전량). totalRevenue는 셀러 기간 매출이라 비중은 셀러 전체 대비다.
     *
     * @throws MalformedRequestException from &gt; to 또는 기간이 {@value #MAX_PERIOD_DAYS}일 초과(400)
     */
    public SellerSalesBreakdownResponse getBreakdown(Long sellerId, LocalDate from, LocalDate to, StatsCompare compare,
            SellerStatsAxis axis) {
        StatsPeriod period = period(from, to);
        long totalRevenue = sellerStatsRepository.sumSales(sellerId, period.start(), period.end()).getRevenue();
        List<AxisRow> rows = aggregate(sellerId, axis, period);
        StatsPeriod comparePeriod = period.compareWith(compare);
        Map<Long, Long> compareRevenue = comparePeriod == null ? Map.of()
                : aggregate(sellerId, axis, comparePeriod).stream().collect(Collectors.toMap(AxisRow::keyId, AxisRow::revenue));

        Map<Long, AxisLabel> labels = labels(axis, rows.stream().map(AxisRow::keyId).toList());
        List<SellerSalesBreakdownRowResponse> responseRows = rows.stream()
                .map(row -> {
                    AxisLabel label = labels.getOrDefault(row.keyId(), new AxisLabel(null, null));
                    // 상품·옵션 이름은 주문 시점 스냅샷 우선 — 이후 상품명·옵션 변경에 과거 집계 표기가 흔들리지 않는다
                    String name = axis == SellerStatsAxis.CATEGORY ? label.name() : row.name();
                    return new SellerSalesBreakdownRowResponse(label.key(), name, row.revenue(), share(row.revenue(), totalRevenue),
                            row.orderCount(), row.quantity(), compareRevenue.get(row.keyId()));
                })
                .toList();
        return new SellerSalesBreakdownResponse(axis, totalRevenue, responseRows);
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

    private SalesSummaryResponse summary(Long sellerId, StatsPeriod period) {
        SellerSalesTotalsProjection sales = sellerStatsRepository.sumSales(sellerId, period.start(), period.end());
        long refund = sellerStatsRepository.sumRefund(sellerId, RefundStatus.COMPLETED, period.start(), period.end());
        return SalesSummaryResponse.of(sales.getRevenue(), refund, sales.getOrderCount(), sales.getQuantity());
    }

    /** 매출은 paid_at·환불은 refunded_at 구간이라 각각 집계 후 dbKey로 합친다. 구간에 없는 키는 0. */
    private List<SalesTrendBucketResponse> trend(Long sellerId, StatsPeriod period, StatsUnit unit,
            List<StatsBuckets.Bucket> buckets) {
        String pattern = StatsBuckets.pattern(unit);
        Map<String, SalesBucketProjection> sales = byBucket(
                sellerStatsRepository.sumSalesByBucket(sellerId, pattern, period.start(), period.end()));
        Map<String, SalesBucketProjection> refunds = byBucket(
                sellerStatsRepository.sumRefundByBucket(sellerId, pattern, RefundStatus.COMPLETED, period.start(), period.end()));
        List<SalesTrendBucketResponse> rows = new ArrayList<>(buckets.size());
        for (StatsBuckets.Bucket bucket : buckets) {
            SalesBucketProjection sale = sales.get(bucket.dbKey());
            SalesBucketProjection refund = refunds.get(bucket.dbKey());
            rows.add(SalesTrendBucketResponse.of(bucket.key(), bucket.label(), amountOf(sale), amountOf(refund), countOf(sale)));
        }
        return rows;
    }

    private List<AxisRow> aggregate(Long sellerId, SellerStatsAxis axis, StatsPeriod period) {
        return switch (axis) {
            case PRODUCT -> adminSalesStatsRepository.aggregateByProductInSeller(sellerId, period.start(), period.end())
                    .stream().map(SellerSalesStatsQueryService::toRow).toList();
            case OPTION -> sellerStatsRepository.aggregateByOption(sellerId, period.start(), period.end())
                    .stream().map(SellerSalesStatsQueryService::toRow).toList();
            case CATEGORY -> sellerStatsRepository.aggregateByCategory(sellerId, period.start(), period.end())
                    .stream().map(SellerSalesStatsQueryService::toRow).toList();
        };
    }

    private static AxisRow toRow(SalesAxisProjection row) {
        return new AxisRow(row.getKeyId(), row.getName(), row.getRevenue(), row.getOrderCount(), row.getQuantity());
    }

    /** 옵션 행 이름 = "상품명 / 옵션라벨" 스냅샷. 단순상품(option_label null)은 "(옵션 없음)". */
    private static AxisRow toRow(SellerSalesOptionProjection row) {
        String optionLabel = row.getOptionLabel() == null ? NO_OPTION_LABEL : row.getOptionLabel();
        return new AxisRow(row.getKeyId(), row.getProductName() + NAME_SEPARATOR + optionLabel, row.getRevenue(),
                row.getOrderCount(), row.getQuantity());
    }

    /** 축 내부 id → 응답 key·이름. PRODUCT·OPTION key = public_id(이름은 스냅샷이라 null)·CATEGORY key = id 문자열·이름 = 현행 표시명. 미존재(soft-delete) id는 맵에 없다(→ null). */
    private Map<Long, AxisLabel> labels(SellerStatsAxis axis, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return switch (axis) {
            case PRODUCT -> productRepository.findByIdIn(ids).stream()
                    .collect(Collectors.toMap(Product::getId, product -> new AxisLabel(product.getPublicId(), null)));
            case OPTION -> productVariantRepository.findByIdIn(ids).stream()
                    .collect(Collectors.toMap(ProductVariant::getId, variant -> new AxisLabel(variant.getPublicId(), null)));
            case CATEGORY -> categoryRepository.findByIdIn(ids).stream().collect(Collectors.toMap(Category::getId,
                    category -> new AxisLabel(String.valueOf(category.getId()), category.getDisplayName())));
        };
    }

    /** 전체 대비 비중(% 소수 2자리). 전체 0이면 0. */
    private static double share(long revenue, long totalRevenue) {
        if (totalRevenue == 0) {
            return 0.0;
        }
        return Math.round(revenue * PERCENT / totalRevenue * SHARE_SCALE) / SHARE_SCALE;
    }

    private static Map<String, SalesBucketProjection> byBucket(List<SalesBucketProjection> rows) {
        return rows.stream().collect(Collectors.toMap(SalesBucketProjection::getBucket, Function.identity()));
    }

    private static long amountOf(SalesBucketProjection row) {
        return row == null ? 0L : row.getAmount();
    }

    private static long countOf(SalesBucketProjection row) {
        return row == null ? 0L : row.getOrderCount();
    }
}

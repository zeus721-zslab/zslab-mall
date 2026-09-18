package com.zslab.mall.stats.service;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.stats.controller.response.AdminSalesBreakdownResponse;
import com.zslab.mall.stats.controller.response.AdminSalesStatsResponse;
import com.zslab.mall.stats.controller.response.SalesBreakdownRowResponse;
import com.zslab.mall.stats.controller.response.SalesSummaryResponse;
import com.zslab.mall.stats.controller.response.SalesTrendBucketResponse;
import com.zslab.mall.stats.enums.StatsAxis;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.repository.AdminSalesStatsRepository;
import com.zslab.mall.stats.repository.SalesAxisProjection;
import com.zslab.mall.stats.repository.SalesBucketProjection;
import com.zslab.mall.stats.repository.SalesTotalsProjection;
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
import org.springframework.util.StringUtils;

/**
 * 관리자 매출 통계 조회(Track 87·D-181·{@code AdminDashboardQueryService} 패턴). 기간·비교 기간 계산(KST 반구간)·구간 키 생성·빈 구간 0 채움·
 * 이름 배치 enrich·응답 조립을 담당하며 집계는 {@link AdminSalesStatsRepository}가 수행한다.
 *
 * <p>구간 키 생성은 {@link StatsBuckets}(Track 88 D-182에서 동작 무변경으로 추출·주문·회원 통계와 공유).
 * 비교 추이는 비교 기간 시작일부터 현재 추이와 같은 개수의 구간을 만들어 인덱스로 대응시킨다(구간 수가 다르면 뒤를 0으로 채우거나 자른다).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminSalesStatsQueryService {

    private static final double SHARE_SCALE = 100.0;
    private static final double PERCENT = 100.0;

    private final AdminSalesStatsRepository statsRepository;
    private final CategoryRepository categoryRepository;
    private final SellerRepository sellerRepository;
    private final ProductRepository productRepository;

    /** 축 행의 응답 key·이름(enrich 결과). */
    private record AxisLabel(String key, String name) {
    }

    /**
     * 요약 + 추이. compare가 NONE이거나 비교 기간에 결제 주문·완료 환불이 모두 0건이면 compareSummary·compareTrend는 null이다.
     *
     * @throws MalformedRequestException from &gt; to(400)
     */
    public AdminSalesStatsResponse getSales(LocalDate from, LocalDate to, StatsUnit unit, StatsCompare compare) {
        StatsPeriod period = StatsPeriod.of(from, to);
        List<StatsBuckets.Bucket> buckets = StatsBuckets.of(period.from(), unit, StatsBuckets.count(period, unit));
        SalesSummaryResponse summary = summary(period);
        List<SalesTrendBucketResponse> trend = trend(period, unit, buckets);

        StatsPeriod comparePeriod = period.compareWith(compare);
        SalesSummaryResponse compareSummary = comparePeriod == null ? null : summary(comparePeriod);
        if (compareSummary == null || compareSummary.hasNoData()) {
            return new AdminSalesStatsResponse(summary, null, trend, null);
        }
        List<StatsBuckets.Bucket> compareBuckets = StatsBuckets.of(comparePeriod.from(), unit, buckets.size());
        return new AdminSalesStatsResponse(summary, compareSummary, trend, trend(comparePeriod, unit, compareBuckets));
    }

    /**
     * 분해 테이블. parentKey가 있으면 그 카테고리(categoryId)·셀러(public_id)의 상품 목록으로 내려간다. 미존재 parentKey는 빈 rows
     * (관리자 주문 목록의 미존재 buyerPublicId 관례). totalRevenue는 기간 전체 결제완료 매출이라 드릴다운에서도 비중은 전체 대비다.
     *
     * @throws MalformedRequestException from &gt; to·PRODUCT 축에 parentKey·CATEGORY parentKey가 숫자가 아닐 때(400)
     */
    public AdminSalesBreakdownResponse getBreakdown(LocalDate from, LocalDate to, StatsCompare compare, StatsAxis axis,
            String parentKey) {
        StatsPeriod period = StatsPeriod.of(from, to);
        String parent = StringUtils.hasText(parentKey) ? parentKey : null;
        if (axis == StatsAxis.PRODUCT && parent != null) {
            throw new MalformedRequestException("PRODUCT 축은 parentKey를 받지 않습니다.");
        }
        Long parentId = parent == null ? null : resolveParentId(axis, parent);
        long totalRevenue = statsRepository.sumSales(period.start(), period.end()).getRevenue();
        if (parent != null && parentId == null) {
            return new AdminSalesBreakdownResponse(axis, parent, totalRevenue, List.of());
        }

        List<SalesAxisProjection> rows = aggregate(axis, parentId, period);
        StatsPeriod comparePeriod = period.compareWith(compare);
        Map<Long, Long> compareRevenue = comparePeriod == null ? Map.of()
                : aggregate(axis, parentId, comparePeriod).stream()
                        .collect(Collectors.toMap(SalesAxisProjection::getKeyId, SalesAxisProjection::getRevenue));

        StatsAxis rowAxis = parent == null ? axis : StatsAxis.PRODUCT;
        boolean drillable = parent == null && axis != StatsAxis.PRODUCT;
        List<Long> keyIds = rows.stream().map(SalesAxisProjection::getKeyId).toList();
        Map<Long, AxisLabel> labels = labels(rowAxis, keyIds);
        List<SalesBreakdownRowResponse> responseRows = rows.stream()
                .map(row -> {
                    AxisLabel label = labels.getOrDefault(row.getKeyId(), new AxisLabel(null, null));
                    // 상품명은 주문 시점 스냅샷(order_item.product_name) 우선 — 이후 상품명 변경에 과거 집계 표기가 흔들리지 않는다
                    String name = rowAxis == StatsAxis.PRODUCT ? row.getName() : label.name();
                    return new SalesBreakdownRowResponse(label.key(), name, row.getRevenue(),
                            share(row.getRevenue(), totalRevenue), row.getOrderCount(), row.getQuantity(),
                            compareRevenue.get(row.getKeyId()), drillable);
                })
                .toList();
        return new AdminSalesBreakdownResponse(axis, parent, totalRevenue, responseRows);
    }

    private SalesSummaryResponse summary(StatsPeriod period) {
        SalesTotalsProjection sales = statsRepository.sumSales(period.start(), period.end());
        long refund = statsRepository.sumRefund(RefundStatus.COMPLETED, period.start(), period.end());
        long itemQuantity = statsRepository.sumItemQuantity(period.start(), period.end());
        return SalesSummaryResponse.of(sales.getRevenue(), refund, sales.getOrderCount(), itemQuantity);
    }

    /** 매출은 paid_at·환불은 refunded_at 구간이라 각각 집계 후 dbKey로 합친다. 구간에 없는 키는 0. */
    private List<SalesTrendBucketResponse> trend(StatsPeriod period, StatsUnit unit, List<StatsBuckets.Bucket> buckets) {
        String pattern = StatsBuckets.pattern(unit);
        Map<String, SalesBucketProjection> sales = byBucket(
                statsRepository.sumSalesByBucket(pattern, period.start(), period.end()));
        Map<String, SalesBucketProjection> refunds = byBucket(
                statsRepository.sumRefundByBucket(pattern, RefundStatus.COMPLETED, period.start(), period.end()));
        List<SalesTrendBucketResponse> rows = new ArrayList<>(buckets.size());
        for (StatsBuckets.Bucket bucket : buckets) {
            SalesBucketProjection sale = sales.get(bucket.dbKey());
            SalesBucketProjection refund = refunds.get(bucket.dbKey());
            rows.add(SalesTrendBucketResponse.of(bucket.key(), bucket.label(), amountOf(sale), amountOf(refund),
                    countOf(sale)));
        }
        return rows;
    }

    /**
     * parentKey → 내부 id(미존재면 null). CATEGORY는 categoryId 숫자 문자열(공개 taxonomy 식별자·CategorySummaryResponse.categoryId),
     * SELLER는 public_id.
     *
     * @throws MalformedRequestException CATEGORY parentKey가 숫자가 아닐 때
     */
    private Long resolveParentId(StatsAxis axis, String parentKey) {
        if (axis == StatsAxis.CATEGORY) {
            long categoryId;
            try {
                categoryId = Long.parseLong(parentKey);
            } catch (NumberFormatException e) {
                throw new MalformedRequestException("CATEGORY 축 parentKey는 categoryId 숫자여야 합니다: " + parentKey);
            }
            return categoryRepository.findByIdIn(List.of(categoryId)).stream().map(Category::getId).findFirst().orElse(null);
        }
        return sellerRepository.findByPublicId(parentKey).map(Seller::getId).orElse(null);
    }

    /** parentId가 있으면 드릴다운(그 카테고리·셀러의 상품별), 없으면 축 최상위 집계. */
    private List<SalesAxisProjection> aggregate(StatsAxis axis, Long parentId, StatsPeriod period) {
        if (parentId != null) {
            return axis == StatsAxis.CATEGORY
                    ? statsRepository.aggregateByProductInCategory(parentId, period.start(), period.end())
                    : statsRepository.aggregateByProductInSeller(parentId, period.start(), period.end());
        }
        return switch (axis) {
            case CATEGORY -> statsRepository.aggregateByCategory(period.start(), period.end());
            case SELLER -> statsRepository.aggregateBySeller(period.start(), period.end());
            case PRODUCT -> statsRepository.aggregateByProduct(period.start(), period.end());
        };
    }

    /** 축 내부 id → 응답 key·이름. CATEGORY key = id 문자열·SELLER/PRODUCT key = public_id. 미존재(soft-delete) id는 맵에 없다(→ null). */
    private Map<Long, AxisLabel> labels(StatsAxis rowAxis, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return switch (rowAxis) {
            case CATEGORY -> categoryRepository.findByIdIn(ids).stream().collect(Collectors.toMap(Category::getId,
                    category -> new AxisLabel(String.valueOf(category.getId()), category.getDisplayName())));
            case SELLER -> sellerRepository.findByIdIn(ids).stream().collect(Collectors.toMap(Seller::getId,
                    seller -> new AxisLabel(seller.getPublicId(), seller.getCompanyName())));
            case PRODUCT -> productRepository.findByIdIn(ids).stream().collect(Collectors.toMap(Product::getId,
                    product -> new AxisLabel(product.getPublicId(), product.getName())));
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

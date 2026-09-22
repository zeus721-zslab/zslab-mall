package com.zslab.mall.dashboard.service;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.policy.LongShippingThreshold;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.dashboard.controller.response.SellerDashboardDailyTrendResponse;
import com.zslab.mall.dashboard.controller.response.SellerDashboardPendingResponse;
import com.zslab.mall.dashboard.controller.response.SellerDashboardPeriodResponse;
import com.zslab.mall.dashboard.controller.response.SellerDashboardRecentClaimResponse;
import com.zslab.mall.dashboard.controller.response.SellerDashboardRecentOrderItemResponse;
import com.zslab.mall.dashboard.controller.response.SellerDashboardResponse;
import com.zslab.mall.dashboard.controller.response.SellerDashboardSummaryResponse;
import com.zslab.mall.dashboard.controller.response.SellerDashboardTopProductResponse;
import com.zslab.mall.dashboard.repository.DashboardBucketProjection;
import com.zslab.mall.dashboard.repository.DashboardTopProductProjection;
import com.zslab.mall.dashboard.repository.SellerDashboardRepository;
import com.zslab.mall.dashboard.repository.SellerDashboardSalesProjection;
import com.zslab.mall.inventory.policy.LowStockThreshold;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 대시보드 조회(Track 90-B-2·read-only·{@code AdminDashboardQueryService} 골격 복제). 셀러 식별·상태 판정은
 * {@code SellerActorResolver}가 끝냈으므로(sellerId 확정) 기간 경계 계산·빈 날 0 채움·상품 public_id enrich·응답 조립만 한다.
 * 집계는 전부 {@link SellerDashboardRepository}(order_item 축)이며 관리자 리포지토리는 쓰지 않는다.
 *
 * <p>기간은 KST 일 단위 양끝 포함({@code from}~{@code to})이고 내부 조건은 반구간 {@code [from 00:00, to+1 00:00)}이다. 기본 최근
 * {@value #DEFAULT_PERIOD_DAYS}일(오늘 포함)·최대 {@value #MAX_PERIOD_DAYS}일. 요약·일별 추이·상위 상품이 기간을 쓰고 처리 대기·최근
 * 목록은 기간 무관이다.
 *
 * <p>쿼리 수 = 요약 2(매출·환불) + 대기 4(PAID 품목·클레임·재고 임박·정산 PENDING) + 일별 버킷 1 + 최근 품목 1 + 최근 클레임 1 +
 * 상위 상품 1 + 상품 public_id 배치 1(상위 상품이 비면 0) = 고정 11·N+1 없음.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerDashboardQueryService {

    static final int DEFAULT_PERIOD_DAYS = 30;
    static final int MAX_PERIOD_DAYS = 92;
    private static final int RECENT_LIMIT = 5;
    private static final int TOP_LIMIT = 5;
    private static final String DAY_BUCKET_PATTERN = "%Y-%m-%d";
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Set<SettlementStatus> PENDING_ONLY = Set.of(SettlementStatus.PENDING);

    private final SellerDashboardRepository sellerDashboardRepository;
    private final SettlementRepository settlementRepository;
    private final ProductRepository productRepository;

    /**
     * @param from 기간 시작일(null이면 to − {@value #DEFAULT_PERIOD_DAYS}일 + 1)
     * @param to   기간 종료일(null이면 오늘)
     * @throws MalformedRequestException from &gt; to 또는 기간이 {@value #MAX_PERIOD_DAYS}일을 초과할 때(400)
     */
    public SellerDashboardResponse getDashboard(Long sellerId, LocalDate from, LocalDate to) {
        LocalDate periodTo = to == null ? LocalDate.now() : to;
        LocalDate periodFrom = from == null ? periodTo.minusDays(DEFAULT_PERIOD_DAYS - 1) : from;
        if (periodFrom.isAfter(periodTo)) {
            throw new MalformedRequestException("from은 to보다 늦을 수 없습니다.");
        }
        long periodDays = ChronoUnit.DAYS.between(periodFrom, periodTo) + 1;
        if (periodDays > MAX_PERIOD_DAYS) {
            throw new MalformedRequestException("기간은 최대 " + MAX_PERIOD_DAYS + "일입니다.");
        }
        LocalDateTime start = periodFrom.atStartOfDay();
        LocalDateTime end = periodTo.plusDays(1).atStartOfDay();

        return new SellerDashboardResponse(
                new SellerDashboardPeriodResponse(periodFrom, periodTo),
                summary(sellerId, start, end),
                pending(sellerId),
                dailyTrend(sellerId, periodFrom, (int) periodDays, start, end),
                recentOrderItems(sellerId),
                recentClaims(sellerId),
                topProducts(sellerId, start, end));
    }

    private SellerDashboardSummaryResponse summary(Long sellerId, LocalDateTime from, LocalDateTime to) {
        SellerDashboardSalesProjection sales = sellerDashboardRepository.sumSales(sellerId, from, to);
        long refund = sellerDashboardRepository.sumRefund(sellerId, RefundStatus.COMPLETED, from, to);
        return SellerDashboardSummaryResponse.of(sales.getRevenue(), refund, sales.getOrderCount());
    }

    private SellerDashboardPendingResponse pending(Long sellerId) {
        return new SellerDashboardPendingResponse(
                sellerDashboardRepository.countOrderItemsByStatus(sellerId, OrderItemStatus.PAID),
                sellerDashboardRepository.countClaimsByStatus(sellerId, ClaimStatus.REQUESTED),
                sellerDashboardRepository.countLowStock(sellerId, LowStockThreshold.MIN, LowStockThreshold.MAX),
                settlementRepository.countBySellerIdAndStatusIn(sellerId, PENDING_ONLY),
                sellerDashboardRepository.countLongShipping(sellerId, DeliveryDirection.OUTBOUND, DeliveryStatus.SHIPPING,
                        LocalDateTime.now().minusDays(LongShippingThreshold.DAYS)));
    }

    /** 기간 내 매일 1행·빈 날 0. */
    private List<SellerDashboardDailyTrendResponse> dailyTrend(Long sellerId, LocalDate firstDay, int days,
            LocalDateTime from, LocalDateTime to) {
        Map<String, DashboardBucketProjection> sales = sellerDashboardRepository
                .sumSalesByBucket(sellerId, DAY_BUCKET_PATTERN, from, to).stream()
                .collect(Collectors.toMap(DashboardBucketProjection::getBucket, Function.identity()));
        List<SellerDashboardDailyTrendResponse> rows = new ArrayList<>(days);
        for (int offset = 0; offset < days; offset++) {
            String key = firstDay.plusDays(offset).format(DAY_KEY);
            DashboardBucketProjection sale = sales.get(key);
            rows.add(new SellerDashboardDailyTrendResponse(key, sale == null ? 0L : sale.getOrderCount(),
                    sale == null ? 0L : sale.getAmount()));
        }
        return rows;
    }

    private List<SellerDashboardRecentOrderItemResponse> recentOrderItems(Long sellerId) {
        return sellerDashboardRepository.findRecentPaidOrderItems(sellerId, limit(RECENT_LIMIT)).stream()
                .map(SellerDashboardRecentOrderItemResponse::from)
                .toList();
    }

    private List<SellerDashboardRecentClaimResponse> recentClaims(Long sellerId) {
        return sellerDashboardRepository.findRecentClaims(sellerId, limit(RECENT_LIMIT)).stream()
                .map(claim -> new SellerDashboardRecentClaimResponse(claim.getClaimPublicId(), claim.getType(),
                        claim.getStatus(), claim.getOrderNo(), claim.getRequestedAt()))
                .toList();
    }

    private List<SellerDashboardTopProductResponse> topProducts(Long sellerId, LocalDateTime from, LocalDateTime to) {
        List<DashboardTopProductProjection> rows = sellerDashboardRepository.findTopProducts(sellerId, from, to, limit(TOP_LIMIT));
        List<Long> productIds = rows.stream().map(DashboardTopProductProjection::getProductId).toList();
        Map<Long, Product> products = productIds.isEmpty() ? Map.of()
                : productRepository.findByIdIn(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        return rows.stream()
                .map(row -> {
                    Product product = products.get(row.getProductId());
                    return new SellerDashboardTopProductResponse(product == null ? null : product.getPublicId(),
                            row.getProductName(), row.getRevenue(), row.getQuantity());
                })
                .toList();
    }

    private static Pageable limit(int size) {
        return PageRequest.of(0, size);
    }
}

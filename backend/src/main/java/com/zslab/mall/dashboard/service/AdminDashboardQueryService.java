package com.zslab.mall.dashboard.service;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.dashboard.controller.response.AdminDashboardResponse;
import com.zslab.mall.dashboard.controller.response.DashboardDailyOrdersResponse;
import com.zslab.mall.dashboard.controller.response.DashboardMonthlyRevenueResponse;
import com.zslab.mall.dashboard.controller.response.DashboardPendingResponse;
import com.zslab.mall.dashboard.controller.response.DashboardPeriodMetrics;
import com.zslab.mall.dashboard.controller.response.DashboardRecentClaimResponse;
import com.zslab.mall.dashboard.controller.response.DashboardRecentOrderResponse;
import com.zslab.mall.dashboard.controller.response.DashboardSummaryResponse;
import com.zslab.mall.dashboard.controller.response.DashboardTopProductResponse;
import com.zslab.mall.dashboard.controller.response.DashboardTopSellerResponse;
import com.zslab.mall.dashboard.repository.AdminDashboardRepository;
import com.zslab.mall.dashboard.repository.DashboardBucketProjection;
import com.zslab.mall.dashboard.repository.DashboardRecentClaimProjection;
import com.zslab.mall.dashboard.repository.DashboardRecentOrderProjection;
import com.zslab.mall.dashboard.repository.DashboardSalesProjection;
import com.zslab.mall.dashboard.repository.DashboardTopProductProjection;
import com.zslab.mall.dashboard.repository.DashboardTopSellerProjection;
import com.zslab.mall.inventory.policy.LowStockThreshold;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 대시보드 조회(Track 86·D-180·{@code AdminSettlementQueryService} 패턴). 기간 경계 계산(KST)·빈 구간 0 채움·이름 배치 enrich·
 * 응답 조립을 담당하며 집계 자체는 {@link AdminDashboardRepository}가 수행한다.
 *
 * <p>기간 경계는 JVM 기본 시간대(운영·로컬 모두 Asia/Seoul)의 오늘을 기준으로 반구간 {@code [start, end)}이다 — 오늘 = 00:00 ~ 익일 00:00,
 * 이번 달 = 1일 00:00 ~ 익월 1일 00:00. 정각(00:00:00) 결제는 포함되고 직전 23:59:59.999999는 제외된다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminDashboardQueryService {

    private static final int MONTHLY_TREND_MONTHS = 6;
    private static final int DAILY_TREND_DAYS = 30;
    private static final int RECENT_LIMIT = 5;
    private static final int TOP_LIMIT = 5;
    private static final String MONTH_BUCKET_PATTERN = "%Y-%m";
    private static final String DAY_BUCKET_PATTERN = "%Y-%m-%d";
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AdminDashboardRepository dashboardRepository;
    private final UserRepository userRepository;
    private final SellerRepository sellerRepository;
    private final ProductRepository productRepository;

    public AdminDashboardResponse getDashboard() {
        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.from(today);
        LocalDateTime thisMonthStart = thisMonth.atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = thisMonth.plusMonths(1).atDay(1).atStartOfDay();

        DashboardSummaryResponse summary = new DashboardSummaryResponse(
                periodMetrics(today.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                periodMetrics(today.minusDays(1).atStartOfDay(), today.atStartOfDay()),
                periodMetrics(thisMonthStart, nextMonthStart),
                periodMetrics(thisMonth.minusMonths(1).atDay(1).atStartOfDay(), thisMonthStart));

        return new AdminDashboardResponse(
                summary,
                pending(),
                monthlyRevenue(thisMonth, nextMonthStart),
                dailyOrders(today),
                recentOrders(),
                recentClaims(),
                topSellers(thisMonthStart, nextMonthStart),
                topProducts(thisMonthStart, nextMonthStart));
    }

    private DashboardPeriodMetrics periodMetrics(LocalDateTime from, LocalDateTime to) {
        DashboardSalesProjection sales = dashboardRepository.sumSales(from, to);
        long refund = dashboardRepository.sumRefund(RefundStatus.COMPLETED, from, to);
        long newMembers = dashboardRepository.countNewMembers(RoleCode.BUYER, from, to);
        return DashboardPeriodMetrics.of(sales.getRevenue(), refund, sales.getOrderCount(), newMembers);
    }

    private DashboardPendingResponse pending() {
        return new DashboardPendingResponse(
                dashboardRepository.countSettlementsByStatus(SettlementStatus.PENDING),
                dashboardRepository.countClaimsByStatus(ClaimStatus.REQUESTED),
                dashboardRepository.countOrderItemsByStatus(OrderItemStatus.PAID),
                dashboardRepository.countLowStock(LowStockThreshold.MIN, LowStockThreshold.MAX),
                dashboardRepository.countProductsByStatus(ProductStatus.PENDING),
                dashboardRepository.countSellersByStatus(SellerStatus.PENDING));
    }

    /** 최근 6개월(당월 포함)·빈 달 0. 매출은 paid_at·환불은 refunded_at 구간이라 각각 집계 후 월 키로 합친다. */
    private List<DashboardMonthlyRevenueResponse> monthlyRevenue(YearMonth thisMonth, LocalDateTime nextMonthStart) {
        YearMonth firstMonth = thisMonth.minusMonths(MONTHLY_TREND_MONTHS - 1);
        LocalDateTime from = firstMonth.atDay(1).atStartOfDay();
        Map<String, DashboardBucketProjection> sales = byBucket(
                dashboardRepository.sumSalesByBucket(MONTH_BUCKET_PATTERN, from, nextMonthStart));
        Map<String, DashboardBucketProjection> refunds = byBucket(
                dashboardRepository.sumRefundByBucket(MONTH_BUCKET_PATTERN, RefundStatus.COMPLETED, from, nextMonthStart));

        List<DashboardMonthlyRevenueResponse> rows = new ArrayList<>(MONTHLY_TREND_MONTHS);
        for (int offset = 0; offset < MONTHLY_TREND_MONTHS; offset++) {
            String key = firstMonth.plusMonths(offset).format(MONTH_KEY);
            DashboardBucketProjection sale = sales.get(key);
            DashboardBucketProjection refund = refunds.get(key);
            rows.add(DashboardMonthlyRevenueResponse.of(key, amountOf(sale), amountOf(refund), countOf(sale)));
        }
        return rows;
    }

    /** 최근 30일(오늘 포함)·빈 날 0. */
    private List<DashboardDailyOrdersResponse> dailyOrders(LocalDate today) {
        LocalDate firstDay = today.minusDays(DAILY_TREND_DAYS - 1);
        Map<String, DashboardBucketProjection> sales = byBucket(dashboardRepository.sumSalesByBucket(
                DAY_BUCKET_PATTERN, firstDay.atStartOfDay(), today.plusDays(1).atStartOfDay()));

        List<DashboardDailyOrdersResponse> rows = new ArrayList<>(DAILY_TREND_DAYS);
        for (int offset = 0; offset < DAILY_TREND_DAYS; offset++) {
            String key = firstDay.plusDays(offset).format(DAY_KEY);
            DashboardBucketProjection sale = sales.get(key);
            rows.add(new DashboardDailyOrdersResponse(key, countOf(sale), amountOf(sale)));
        }
        return rows;
    }

    private List<DashboardRecentOrderResponse> recentOrders() {
        List<DashboardRecentOrderProjection> orders = dashboardRepository.findRecentPaidOrders(limit(RECENT_LIMIT));
        List<Long> buyerIds = orders.stream().map(DashboardRecentOrderProjection::getBuyerId).distinct().toList();
        Map<Long, User> buyers = buyerIds.isEmpty() ? Map.of()
                : userRepository.findByIdIn(buyerIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return orders.stream()
                .map(order -> {
                    User buyer = buyers.get(order.getBuyerId());
                    return new DashboardRecentOrderResponse(order.getOrderPublicId(), order.getOrderNo(),
                            buyer == null ? null : buyer.getName(), order.getTotalPrice(), order.getPaidAt(),
                            order.getStatus());
                })
                .toList();
    }

    private List<DashboardRecentClaimResponse> recentClaims() {
        return dashboardRepository.findRecentClaims(limit(RECENT_LIMIT)).stream()
                .map(claim -> new DashboardRecentClaimResponse(claim.getClaimPublicId(), claim.getType(),
                        claim.getStatus(), claim.getOrderNo(), claim.getRequestedAt()))
                .toList();
    }

    private List<DashboardTopSellerResponse> topSellers(LocalDateTime from, LocalDateTime to) {
        List<DashboardTopSellerProjection> rows = dashboardRepository.findTopSellers(from, to, limit(TOP_LIMIT));
        List<Long> sellerIds = rows.stream().map(DashboardTopSellerProjection::getSellerId).toList();
        Map<Long, Seller> sellers = sellerIds.isEmpty() ? Map.of()
                : sellerRepository.findByIdIn(sellerIds).stream().collect(Collectors.toMap(Seller::getId, Function.identity()));
        return rows.stream()
                .map(row -> {
                    Seller seller = sellers.get(row.getSellerId());
                    return new DashboardTopSellerResponse(seller == null ? null : seller.getPublicId(),
                            seller == null ? null : seller.getCompanyName(), row.getRevenue(), row.getOrderItemCount());
                })
                .toList();
    }

    private List<DashboardTopProductResponse> topProducts(LocalDateTime from, LocalDateTime to) {
        List<DashboardTopProductProjection> rows = dashboardRepository.findTopProducts(from, to, limit(TOP_LIMIT));
        List<Long> productIds = rows.stream().map(DashboardTopProductProjection::getProductId).toList();
        Map<Long, Product> products = productIds.isEmpty() ? Map.of()
                : productRepository.findByIdIn(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        return rows.stream()
                .map(row -> {
                    Product product = products.get(row.getProductId());
                    return new DashboardTopProductResponse(product == null ? null : product.getPublicId(),
                            row.getProductName(), row.getRevenue(), row.getQuantity());
                })
                .toList();
    }

    private static Map<String, DashboardBucketProjection> byBucket(List<DashboardBucketProjection> rows) {
        return rows.stream().collect(Collectors.toMap(DashboardBucketProjection::getBucket, Function.identity()));
    }

    private static long amountOf(DashboardBucketProjection row) {
        return row == null ? 0L : row.getAmount();
    }

    private static long countOf(DashboardBucketProjection row) {
        return row == null ? 0L : row.getOrderCount();
    }

    private static Pageable limit(int size) {
        return PageRequest.of(0, size);
    }
}

package com.zslab.mall.stats.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.ProductVariantStatus;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.stats.controller.response.SellerProductRankResponse;
import com.zslab.mall.stats.controller.response.SellerProductStatsResponse;
import com.zslab.mall.stats.controller.response.SellerStockTurnoverResponse;
import com.zslab.mall.stats.controller.response.SellerUnsoldProductResponse;
import com.zslab.mall.stats.repository.AdminSalesStatsRepository;
import com.zslab.mall.stats.repository.SalesAxisProjection;
import com.zslab.mall.stats.repository.SellerOptionStockProjection;
import com.zslab.mall.stats.repository.SellerProductQuantityProjection;
import com.zslab.mall.stats.repository.SellerStatsRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 상품 통계 조회(Track 90-E-3·D-200). 관리자 대응 API가 없어 흐름 복제 대상은 없고, 판매 집계는 90-E-1과 같은
 * {@link AdminSalesStatsRepository#aggregateByProductInSeller}(order_item.seller_id·paid_at·매출 DESC)를 재사용하며 미판매·입고·현재 가용·품절 옵션은
 * {@link SellerStatsRepository}(product.seller_id 축)다. 기간은 {@link StatsPeriod}(최대 {@value #MAX_PERIOD_DAYS}일)·비교·버킷 없음.
 *
 * <p>쿼리 수 = 판매 집계 1 + 미판매 1 + SALE 상품 1 + 입고 1 + 현재 가용 1 + 품절 옵션 1 + 상품 public_id 배치 1(상위/하위가 비면 0) = 고정 7·N+1 없음.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerProductStatsQueryService {

    static final int MAX_PERIOD_DAYS = SellerSalesStatsQueryService.MAX_PERIOD_DAYS;
    /** 판매 상위·하위 각 최대 행 수. */
    static final int RANK_LIMIT = 10;

    private final SellerStatsRepository sellerStatsRepository;
    private final AdminSalesStatsRepository adminSalesStatsRepository;
    private final ProductRepository productRepository;

    /**
     * 상위·하위·미판매·재고 회전·현재 품절 옵션 수.
     *
     * @throws MalformedRequestException from &gt; to 또는 기간이 {@value #MAX_PERIOD_DAYS}일 초과(400)
     */
    public SellerProductStatsResponse getProductStats(Long sellerId, LocalDate from, LocalDate to) {
        StatsPeriod period = StatsPeriod.of(from, to);
        if (period.dayCount() > MAX_PERIOD_DAYS) {
            throw new MalformedRequestException("기간은 최대 " + MAX_PERIOD_DAYS + "일입니다.");
        }
        List<SalesAxisProjection> sold = adminSalesStatsRepository.aggregateByProductInSeller(sellerId, period.start(), period.end());
        Map<Long, String> soldKeys = publicIds(sold.stream().map(SalesAxisProjection::getKeyId).toList());
        List<SellerProductRankResponse> ranked = sold.stream()
                .map(row -> new SellerProductRankResponse(soldKeys.get(row.getKeyId()), row.getName(), row.getRevenue(),
                        row.getOrderCount(), row.getQuantity()))
                .toList();
        List<SellerProductRankResponse> top = ranked.stream().limit(RANK_LIMIT).toList();
        // 하위 = 상위 10에 들지 않은 나머지(11번째 이후)를 역순(매출 ASC·동률 productId DESC)으로 최대 10 — 상위와 교집합 0.
        // 판매 0 상품은 미판매로 분리한다. 판매 상품이 10 이하면 하위는 빈 목록(FE 안내 문구).
        List<SellerProductRankResponse> rest = new ArrayList<>(ranked.subList(Math.min(RANK_LIMIT, ranked.size()), ranked.size()));
        Collections.reverse(rest);
        List<SellerProductRankResponse> bottom = rest.stream().limit(RANK_LIMIT).toList();

        List<SellerUnsoldProductResponse> unsold = sellerStatsRepository
                .findUnsoldProducts(sellerId, ProductStatus.SALE, period.start(), period.end()).stream()
                .map(product -> new SellerUnsoldProductResponse(product.getPublicId(), product.getName(), product.getBasePrice()))
                .toList();

        List<SellerStockTurnoverResponse> turnover = stockTurnover(sellerId, period, sold);
        SellerOptionStockProjection options = sellerStatsRepository.countSoldOutOptions(sellerId, ProductStatus.SALE, ProductVariantStatus.SALE);
        return new SellerProductStatsResponse(period.dayCount(), top, bottom, unsold, turnover, options.getSoldOutCount(),
                options.getTotalCount());
    }

    /** SALE 상품 단위: 입고(기간)·판매(기간·상위 집계 재사용)·현재 가용·소진 예상일. 소진 예상 ASC(null = 판매 없음은 뒤·동률 productKey = public_id ASC). */
    private List<SellerStockTurnoverResponse> stockTurnover(Long sellerId, StatsPeriod period, List<SalesAxisProjection> sold) {
        List<Product> products = sellerStatsRepository.findProductsByStatus(sellerId, ProductStatus.SALE);
        if (products.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> soldQuantity = sold.stream().collect(Collectors.toMap(SalesAxisProjection::getKeyId, SalesAxisProjection::getQuantity));
        Map<Long, Long> inbound = quantities(sellerStatsRepository.sumInboundByProduct(sellerId, InventoryHistoryChangeType.INBOUND,
                period.start(), period.end()));
        Map<Long, Long> available = quantities(sellerStatsRepository.sumAvailableByProduct(sellerId));
        return products.stream()
                .map(product -> {
                    long soldCount = soldQuantity.getOrDefault(product.getId(), 0L);
                    long availableCount = available.getOrDefault(product.getId(), 0L);
                    return new SellerStockTurnoverResponse(product.getPublicId(), product.getName(),
                            inbound.getOrDefault(product.getId(), 0L), soldCount, availableCount,
                            depletionDays(availableCount, soldCount, period.dayCount()));
                })
                .sorted(Comparator.comparing(SellerStockTurnoverResponse::depletionDays, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SellerStockTurnoverResponse::productKey))
                .toList();
    }

    /** 소진 예상일 = ceil(가용 × 기간 일수 ÷ 판매). 판매 0이면 null(0 나누기 금지·"판매 없음")·가용 0이면 0. */
    static Long depletionDays(long available, long sold, long periodDays) {
        if (sold <= 0) {
            return null;
        }
        if (available <= 0) {
            return 0L;
        }
        return Math.ceilDiv(available * periodDays, sold);
    }

    private static Map<Long, Long> quantities(List<SellerProductQuantityProjection> rows) {
        return rows.stream().collect(Collectors.toMap(SellerProductQuantityProjection::getKeyId, SellerProductQuantityProjection::getQuantity));
    }

    /** 상품 id → public_id. 미존재(soft-delete) id는 맵에 없다(→ null·FE 삭제 표기). */
    private Map<Long, String> publicIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productRepository.findByIdIn(ids).stream().collect(Collectors.toMap(Product::getId, Product::getPublicId));
    }
}

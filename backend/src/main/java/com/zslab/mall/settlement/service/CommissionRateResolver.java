package com.zslab.mall.settlement.service;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.repository.SellerRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 수수료율 3단 판정(Track 85): 셀러 개별율(seller.commission_rate NOT NULL) → 카테고리율(category.commission_rate NOT NULL) →
 * 플랫폼 기본율({@code settlement.default-commission-rate}·기본 1000 bp). 체크아웃이 주문 시점에 판정해 order_item에 스냅샷하며,
 * 정산은 스냅샷만 쓴다(사후 율 변경 무영향).
 *
 * <p>다건 판정 {@link #resolveAll}은 셀러·카테고리를 각각 배치 1회로 조회한다(체크아웃 N+1 금지). 미존재 셀러·카테고리는 해당 단계를
 * 건너뛴다(주문 성립 자체는 상품·변형 해소가 보장·율은 다음 단계로 fallback).
 *
 * <p>율 범위는 {@value #MIN_COMMISSION_RATE}~{@value #MAX_COMMISSION_RATE} bp다. 설정 기본값은 기동 시(생성자), 셀러·카테고리 저장값은
 * 판정 시 검증해 범위 밖이면 예외로 주문 진행을 막는다(음수·100% 초과 수수료가 order_item에 스냅샷되는 것 차단).
 */
@Component
public class CommissionRateResolver {

    public static final int MIN_COMMISSION_RATE = 0;
    public static final int MAX_COMMISSION_RATE = 10_000;

    private final SellerRepository sellerRepository;
    private final CategoryRepository categoryRepository;
    @Getter
    private final int defaultCommissionRate;

    public CommissionRateResolver(SellerRepository sellerRepository, CategoryRepository categoryRepository,
            @Value("${settlement.default-commission-rate:1000}") int defaultCommissionRate) {
        this.sellerRepository = sellerRepository;
        this.categoryRepository = categoryRepository;
        this.defaultCommissionRate = requireInRange(defaultCommissionRate, "settlement.default-commission-rate");
    }

    /**
     * 수수료율 범위 검증(0 ≤ rate ≤ 10000 bp).
     *
     * @throws IllegalArgumentException 범위 밖일 때
     */
    public static int requireInRange(int rate, String source) {
        if (rate < MIN_COMMISSION_RATE || rate > MAX_COMMISSION_RATE) {
            throw new IllegalArgumentException("수수료율 범위 위반(" + MIN_COMMISSION_RATE + "~" + MAX_COMMISSION_RATE
                    + " bp): " + source + "=" + rate);
        }
        return rate;
    }

    /** 판정 키(셀러·카테고리 조합). categoryId는 null 허용(카테고리 단계 생략). */
    public record CommissionRateKey(Long sellerId, Long categoryId) {
    }

    /** 단건 판정. */
    public int resolve(Long sellerId, Long categoryId) {
        return resolveAll(List.of(new CommissionRateKey(sellerId, categoryId))).get(new CommissionRateKey(sellerId, categoryId));
    }

    /** 다건 판정(배치 조회 2회). 반환 Map은 입력 키 전부를 담는다. */
    public Map<CommissionRateKey, Integer> resolveAll(Collection<CommissionRateKey> keys) {
        Set<Long> sellerIds = new LinkedHashSet<>();
        Set<Long> categoryIds = new LinkedHashSet<>();
        for (CommissionRateKey key : keys) {
            if (key.sellerId() != null) {
                sellerIds.add(key.sellerId());
            }
            if (key.categoryId() != null) {
                categoryIds.add(key.categoryId());
            }
        }
        Map<Long, Seller> sellerById = sellerIds.isEmpty() ? Map.of()
                : sellerRepository.findByIdIn(sellerIds).stream()
                        .collect(Collectors.toMap(Seller::getId, Function.identity()));
        Map<Long, Category> categoryById = categoryIds.isEmpty() ? Map.of()
                : categoryRepository.findByIdIn(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity()));

        Map<CommissionRateKey, Integer> resolved = new HashMap<>();
        for (CommissionRateKey key : keys) {
            // Map.of()(불변)는 get(null)에 NPE를 던지므로 null 키는 조회하지 않는다
            Seller seller = key.sellerId() == null ? null : sellerById.get(key.sellerId());
            Category category = key.categoryId() == null ? null : categoryById.get(key.categoryId());
            resolved.put(key, resolveOne(seller, category));
        }
        return resolved;
    }

    private int resolveOne(Seller seller, Category category) {
        if (seller != null && seller.getCommissionRate() != null) {
            return requireInRange(seller.getCommissionRate(), "seller.commission_rate(id=" + seller.getId() + ")");
        }
        if (category != null && category.getCommissionRate() != null) {
            return requireInRange(category.getCommissionRate(), "category.commission_rate(id=" + category.getId() + ")");
        }
        return defaultCommissionRate;
    }
}

package com.zslab.mall.category.service;

import com.zslab.mall.category.controller.response.AdminCategoryListResponse;
import com.zslab.mall.category.controller.response.AdminCategorySummaryResponse;
import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.product.repository.CategoryProductCountProjection;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.settlement.service.CommissionRateResolver;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 카테고리 조회(Track 89-C D-185·read-only). 루트 카테고리 전량 + 카테고리별 활성 상품 수(배치 1쿼리) + 플랫폼 기본율.
 * 삭제 카테고리는 {@code @SQLRestriction}이 제외한다(복구 API가 없어 includeDeleted는 두지 않는다).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminCategoryQueryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CommissionRateResolver commissionRateResolver;

    public AdminCategoryListResponse listRootCategories() {
        List<Category> categories = categoryRepository.findByParentIsNullOrderBySortOrderAscIdAsc();
        List<Long> categoryIds = categories.stream().map(Category::getId).toList();
        Map<Long, Long> productCountById = categoryIds.isEmpty() ? Map.of()
                : productRepository.countActiveByCategoryIds(categoryIds).stream()
                        .collect(Collectors.toMap(CategoryProductCountProjection::getCategoryId,
                                CategoryProductCountProjection::getProductCount, (left, right) -> left));
        List<AdminCategorySummaryResponse> items = categories.stream()
                .map(category -> AdminCategorySummaryResponse.from(
                        category, productCountById.getOrDefault(category.getId(), 0L)))
                .toList();
        return new AdminCategoryListResponse(commissionRateResolver.getDefaultCommissionRate(), items);
    }
}

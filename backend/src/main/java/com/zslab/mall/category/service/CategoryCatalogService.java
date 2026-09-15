package com.zslab.mall.category.service;

import com.zslab.mall.category.controller.response.CategorySummaryResponse;
import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 카테고리 조회 서비스(Track 72·read-only). 쓰기 경로 {@link CategoryService}(Track 46)와 분리해 읽기 전용 트랜잭션으로
 * 루트 카테고리 목록을 제공한다({@code ProductCatalogService} 읽기 계층 선례).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CategoryCatalogService {

    private final CategoryRepository categoryRepository;

    /** 루트 카테고리(parent IS NULL) 목록. sort_order·id 오름차순. 없으면 빈 목록. */
    public List<CategorySummaryResponse> listRootCategories() {
        return categoryRepository.findByParentIsNullOrderBySortOrderAscIdAsc().stream()
                .map(CategoryCatalogService::toSummary)
                .toList();
    }

    private static CategorySummaryResponse toSummary(Category category) {
        return new CategorySummaryResponse(category.getId(), category.getDisplayName(), category.getSortOrder());
    }
}

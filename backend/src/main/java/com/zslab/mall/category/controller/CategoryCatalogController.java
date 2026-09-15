package com.zslab.mall.category.controller;

import com.zslab.mall.category.controller.response.CategorySummaryResponse;
import com.zslab.mall.category.service.CategoryCatalogService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 카테고리 목록 REST 컨트롤러(Track 72). 액터 중립 URL(/api/v1/categories)이며 GET은 SecurityConfig에서 permitAll
 * (공개 taxonomy)이라 인증 없이 접근 가능하다. HTTP 책임만 가진다: Service 위임·200 변환.
 */
@RestController
public class CategoryCatalogController {

    private final CategoryCatalogService categoryCatalogService;

    public CategoryCatalogController(CategoryCatalogService categoryCatalogService) {
        this.categoryCatalogService = categoryCatalogService;
    }

    /** 루트 카테고리 목록(sort_order·id 오름차순). 페이징 없음(루트 개수 소량). */
    @GetMapping("/api/v1/categories")
    public ResponseEntity<List<CategorySummaryResponse>> listRoots() {
        return ResponseEntity.ok(categoryCatalogService.listRootCategories());
    }
}

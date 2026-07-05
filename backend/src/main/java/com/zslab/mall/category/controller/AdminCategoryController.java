package com.zslab.mall.category.controller;

import com.zslab.mall.category.controller.request.CreateCategoryRequest;
import com.zslab.mall.category.controller.response.CreateCategoryResponse;
import com.zslab.mall.category.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 카테고리 생성 REST 컨트롤러(Track 46). 관리자 주도 루트 카테고리 생성 1 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link com.zslab.mall.seller.controller.AdminSellerController}
 * 선례). 인가는 SecurityConfig의 {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제하므로 메서드 @PreAuthorize를
 * 두지 않는다. HTTP 책임만 가진다: 요청 검증·Service 위임·HTTP 변환. 생성·중복 가드는 {@link CategoryService} 책임이다.
 */
@RestController
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 관리자 주도 루트 카테고리 생성(Track 46). 성공 201 + categoryId. 중복 displayName 409·검증 실패 400
     * ({@link CategoryService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/categories")
    public ResponseEntity<CreateCategoryResponse> create(
            @RequestBody @Valid CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.createRootCategory(request));
    }
}

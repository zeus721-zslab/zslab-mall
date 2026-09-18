package com.zslab.mall.category.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.category.controller.request.CreateCategoryRequest;
import com.zslab.mall.category.controller.request.ReorderCategoriesRequest;
import com.zslab.mall.category.controller.request.UpdateCategoryRequest;
import com.zslab.mall.category.controller.response.AdminCategoryListResponse;
import com.zslab.mall.category.controller.response.CreateCategoryResponse;
import com.zslab.mall.category.service.AdminCategoryQueryService;
import com.zslab.mall.category.service.CategoryService;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 카테고리 REST 컨트롤러(Track 46 생성 → Track 89-C D-185 목록·수정·삭제·정렬 추가).
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link com.zslab.mall.seller.controller.AdminSellerController}
 * 선례). 인가는 SecurityConfig의 {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제하므로 메서드 @PreAuthorize를
 * 두지 않는다. HTTP 책임만 가진다: 요청 검증·액터 해소·Service 위임·HTTP 변환. 가드·감사는 {@link CategoryService} 책임이다.
 * 수정·삭제는 감사 컨텍스트가 필요해 {@code X-Admin-Id} 헤더(D-93·{@link AdminActorResolver})를 해소한다.
 */
@RestController
public class AdminCategoryController {

    private final CategoryService categoryService;
    private final AdminCategoryQueryService adminCategoryQueryService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminCategoryController(CategoryService categoryService, AdminCategoryQueryService adminCategoryQueryService,
            AdminActorResolver adminActorResolver, ActorRoleResolver actorRoleResolver) {
        this.categoryService = categoryService;
        this.adminCategoryQueryService = adminCategoryQueryService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 관리자 카테고리 목록(Track 89-C). 루트 전량·sort_order 순·활성 상품 수·플랫폼 기본율 동봉. 삭제 카테고리 제외. */
    @GetMapping("/api/v1/admin/categories")
    public AdminCategoryListResponse list() {
        return adminCategoryQueryService.listRootCategories();
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

    /**
     * 카테고리 수정(Track 89-C·PUT 전체 치환). 3필드 필수·commissionRate 0~10000 bp 또는 null(미설정)·범위 밖 400·displayName 중복 409·
     * 수수료율 실변경 시 사유 공백 400(MALFORMED_REQUEST)·값 무변경이면 감사 없이 204. 미존재 404. 화면은 목록을 재조회하므로 본문 없음.
     */
    @PutMapping("/api/v1/admin/categories/{categoryId}")
    public ResponseEntity<Void> update(
            @PathVariable Long categoryId,
            @RequestBody @Valid UpdateCategoryRequest request,
            HttpServletRequest httpRequest) {
        categoryService.update(categoryId, request, auditContext(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** 카테고리 soft-delete(Track 89-C). 활성 상품 0건일 때만 204·1건 이상 409 CATEGORY_HAS_PRODUCTS·미존재 404. */
    @DeleteMapping("/api/v1/admin/categories/{categoryId}")
    public ResponseEntity<Void> delete(@PathVariable Long categoryId, HttpServletRequest httpRequest) {
        categoryService.delete(categoryId, auditContext(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** 루트 카테고리 일괄 정렬 변경(Track 89-C). 활성 루트 전체 id 배열·index=sortOrder. 누락·중복·미존재 400. 성공 204. */
    @PatchMapping("/api/v1/admin/categories/order")
    public ResponseEntity<Void> reorder(@RequestBody @Valid ReorderCategoriesRequest request) {
        categoryService.reorder(request.categoryIds());
        return ResponseEntity.noContent().build();
    }

    private AuditContext auditContext(HttpServletRequest httpRequest) {
        return AuditContext.of(adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
    }
}

package com.zslab.mall.product.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.controller.request.AdminProductBulkSoldOutRequest;
import com.zslab.mall.product.controller.request.AdminProductBulkStatusRequest;
import com.zslab.mall.product.controller.request.AdminProductCreateRequest;
import com.zslab.mall.product.controller.request.AdminProductImagesRequest;
import com.zslab.mall.product.controller.request.AdminProductSoldOutRequest;
import com.zslab.mall.product.controller.request.AdminProductSort;
import com.zslab.mall.product.controller.request.AdminProductStockFilter;
import com.zslab.mall.product.controller.request.AdminProductUpdateRequest;
import com.zslab.mall.product.controller.request.AdminProductVariantsRequest;
import com.zslab.mall.product.controller.response.AdminProductBulkResponse;
import com.zslab.mall.product.controller.response.AdminProductDetailResponse;
import com.zslab.mall.product.controller.response.AdminProductSummaryResponse;
import com.zslab.mall.product.controller.response.ProductRegistrationResponse;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.service.AdminProductBulkService;
import com.zslab.mall.product.service.AdminProductCommandService;
import com.zslab.mall.product.service.AdminProductQueryService;
import com.zslab.mall.product.service.AdminProductVariantService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 상품 관리(조회·등록·수정·삭제·일괄) REST 컨트롤러(Track 76). 상태 전이(승인·거부·sale-status)는 기존
 * {@link AdminProductController}가 그대로 담당한다(scripts/admin-product-status.ps1 호환·FE-25 후 정리 이월).
 *
 * <p>클래스 레벨 base path 없이 메서드 절대경로를 부여한다(AdminProductController 선례). 인가는 SecurityConfig의
 * {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제한다. HTTP 책임만 가진다: 파라미터 바인딩·검증·감사 컨텍스트 조립·
 * Service 위임·HTTP 변환. 검증 실패 400은 fieldErrors 목록을 병기한다(GlobalExceptionHandler·Track 76).
 */
@RestController
public class AdminProductManagementController {

    private final AdminProductQueryService adminProductQueryService;
    private final AdminProductCommandService adminProductCommandService;
    private final AdminProductVariantService adminProductVariantService;
    private final AdminProductBulkService adminProductBulkService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminProductManagementController(
            AdminProductQueryService adminProductQueryService,
            AdminProductCommandService adminProductCommandService,
            AdminProductVariantService adminProductVariantService,
            AdminProductBulkService adminProductBulkService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.adminProductQueryService = adminProductQueryService;
        this.adminProductCommandService = adminProductCommandService;
        this.adminProductVariantService = adminProductVariantService;
        this.adminProductBulkService = adminProductBulkService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    private AuditContext auditContext(HttpServletRequest request) {
        return AuditContext.of(adminActorResolver.resolve(request), actorRoleResolver.requireCoarseRole());
    }

    // ==================== 조회 ====================

    /**
     * 관리자 상품 목록(PagedResponse). keyword=상품명 부분일치 또는 public_id 정확일치·status·soldOut·sellerPublicId·categoryId·
     * stockFilter(LOW/OUT/IN_STOCK·Track 89-A) 필터·sort(LATEST 기본)·page/size(1~100). sellerPublicId 미존재 404·keyword 50자 초과·
     * stockFilter 허용 외 값 400.
     */
    @GetMapping("/api/v1/admin/products")
    public ResponseEntity<PagedResponse<AdminProductSummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) Boolean soldOut,
            @RequestParam(required = false) String sellerPublicId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) AdminProductStockFilter stockFilter,
            @RequestParam(defaultValue = "LATEST") AdminProductSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminProductQueryService.listProducts(
                keyword, status, soldOut, sellerPublicId, categoryId, stockFilter, sort, page, size));
    }

    /** 수정 화면용 상세(이미지·옵션·variant·재고 포함·내부 id 노출). 미존재·삭제 404. */
    @GetMapping("/api/v1/admin/products/{publicId}")
    public ResponseEntity<AdminProductDetailResponse> getOne(@PathVariable String publicId) {
        return ResponseEntity.ok(adminProductQueryService.getProduct(publicId));
    }

    // ==================== 등록·수정 ====================

    /** 관리자 상품 등록(셀러 지정·PENDING). 201 + product/variant public_id. 셀러 404·카테고리 404·옵션 조합 중복 409. */
    @PostMapping("/api/v1/admin/products")
    public ResponseEntity<ProductRegistrationResponse> create(
            @Valid @RequestBody AdminProductCreateRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminProductCommandService.create(body, auditContext(request)));
    }

    /** 기본정보 수정(전체 치환). 200 + 수정 후 상세. 미존재 404·카테고리 404·판매기간 역전 400. */
    @PutMapping("/api/v1/admin/products/{publicId}")
    public ResponseEntity<AdminProductDetailResponse> update(@PathVariable String publicId,
            @Valid @RequestBody AdminProductUpdateRequest body, HttpServletRequest request) {
        adminProductCommandService.update(publicId, body, auditContext(request));
        return ResponseEntity.ok(adminProductQueryService.getProduct(publicId));
    }

    /** 이미지 메타 전체 치환(URL·유형·순서·대표·업로드는 Track 77). 200 + 수정 후 상세. imageId 미존재 404·대표 규칙 위반 400. */
    @PutMapping("/api/v1/admin/products/{publicId}/images")
    public ResponseEntity<AdminProductDetailResponse> replaceImages(@PathVariable String publicId,
            @Valid @RequestBody AdminProductImagesRequest body, HttpServletRequest request) {
        adminProductCommandService.replaceImages(publicId, body, auditContext(request));
        return ResponseEntity.ok(adminProductQueryService.getProduct(publicId));
    }

    /** variant 전체 치환(D9 α·추가·메타 수정·soft-delete). 200 + 수정 후 상세. variant 404·옵션 불일치 400·조합 중복 409. */
    @PutMapping("/api/v1/admin/products/{publicId}/variants")
    public ResponseEntity<AdminProductDetailResponse> replaceVariants(@PathVariable String publicId,
            @Valid @RequestBody AdminProductVariantsRequest body, HttpServletRequest request) {
        adminProductVariantService.replaceVariants(publicId, body, auditContext(request));
        return ResponseEntity.ok(adminProductQueryService.getProduct(publicId));
    }

    /** 상품 단위 수동 품절 on/off. 200 + 수정 후 상세. */
    @PatchMapping("/api/v1/admin/products/{publicId}/soldout")
    public ResponseEntity<AdminProductDetailResponse> changeSoldOut(@PathVariable String publicId,
            @Valid @RequestBody AdminProductSoldOutRequest body, HttpServletRequest request) {
        adminProductCommandService.changeSoldOut(publicId, body.soldOut(), auditContext(request));
        return ResponseEntity.ok(adminProductQueryService.getProduct(publicId));
    }

    // ==================== 일괄 ====================

    /** 일괄 상태 변경(SALE·STOPPED·PENDING→SALE은 승인). 항상 200·항목별 결과(부분 실패 허용). */
    @PostMapping("/api/v1/admin/products/bulk/status")
    public ResponseEntity<AdminProductBulkResponse> bulkStatus(
            @Valid @RequestBody AdminProductBulkStatusRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(adminProductBulkService.changeStatus(
                body.productPublicIds(), ProductStatus.valueOf(body.status()), auditContext(request)));
    }

    /** 일괄 수동 품절 변경. 항상 200·항목별 결과(부분 실패 허용). */
    @PostMapping("/api/v1/admin/products/bulk/soldout")
    public ResponseEntity<AdminProductBulkResponse> bulkSoldOut(
            @Valid @RequestBody AdminProductBulkSoldOutRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(adminProductBulkService.changeSoldOut(
                body.productPublicIds(), body.soldOut(), auditContext(request)));
    }

    // ==================== 삭제 ====================

    /** soft-delete. 204. 미존재 404·주문 이력 존재 409 PRODUCT_HAS_ORDER_HISTORY(판매중지 안내). */
    @DeleteMapping("/api/v1/admin/products/{publicId}")
    public ResponseEntity<Void> delete(@PathVariable String publicId, HttpServletRequest request) {
        adminProductCommandService.delete(publicId, auditContext(request));
        return ResponseEntity.noContent().build();
    }
}

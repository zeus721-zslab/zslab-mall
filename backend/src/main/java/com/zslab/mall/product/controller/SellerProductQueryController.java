package com.zslab.mall.product.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.controller.request.SellerProductSort;
import com.zslab.mall.product.controller.response.SellerProductDetailResponse;
import com.zslab.mall.product.controller.response.SellerProductSummaryResponse;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.service.SellerProductQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 상품 조회 REST 컨트롤러(Track 90-C-1). 자기 상품 목록·상세 2 endpoint. 식별자는 public_id({@code prd_})이며 기존 셀러
 * 이미지 API({@link SellerProductImageController}·내부 PK 경로)는 손대지 않는다. URL prefix {@code /api/v1/seller/**}는 SecurityConfig가
 * hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드(D-190·GET이라 SUSPENDED 통과)는 {@link SellerActorResolver}가 한다.
 * 등록은 기존 {@link ProductRegistrationController}가 담당한다.
 */
@RestController
public class SellerProductQueryController {

    private final SellerProductQueryService sellerProductQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerProductQueryController(SellerProductQueryService sellerProductQueryService,
            SellerActorResolver sellerActorResolver) {
        this.sellerProductQueryService = sellerProductQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 셀러 상품 목록. keyword(상품명 부분일치)·status·categoryId·sort(LATEST 기본)·page/size(1~100). 허용 외 enum 400·keyword 50자 초과 400. */
    @GetMapping("/api/v1/seller/products")
    public ResponseEntity<PagedResponse<SellerProductSummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "LATEST") SellerProductSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerProductQueryService.listProducts(sellerId, keyword, status, categoryId, sort, page, size));
    }

    /** 셀러 상품 상세(이미지·옵션·variant·재고). 미존재·삭제·타 셀러 404(존재 은닉). */
    @GetMapping("/api/v1/seller/products/{productPublicId}")
    public ResponseEntity<SellerProductDetailResponse> get(@PathVariable String productPublicId, HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerProductQueryService.getProduct(sellerId, productPublicId));
    }
}

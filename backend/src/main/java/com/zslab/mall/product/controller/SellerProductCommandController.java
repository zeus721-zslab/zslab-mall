package com.zslab.mall.product.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.product.controller.request.SellerProductImagesRequest;
import com.zslab.mall.product.controller.request.SellerProductUpdateRequest;
import com.zslab.mall.product.controller.request.SellerProductVariantsRequest;
import com.zslab.mall.product.controller.response.SellerProductDetailResponse;
import com.zslab.mall.product.service.SellerProductCommandService;
import com.zslab.mall.product.service.SellerProductQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 상품 수정 REST 컨트롤러(Track 90-C-2). 기본정보·이미지·variant PUT 3 endpoint. 식별자는 public_id({@code prd_}). 조회는
 * {@link SellerProductQueryController}, 등록은 {@link ProductRegistrationController}, 상태 변경(승인·판매중지)은 관리자 소관이다.
 * URL prefix {@code /api/v1/seller/**}는 SecurityConfig가 hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드(D-190·PUT이라 SUSPENDED 403)는
 * {@link SellerActorResolver}가 한다. HTTP 책임만 가진다(D-40 β′): 액터 해소·@Valid·Service 위임·수정 후 상세 재조회 응답.
 */
@RestController
public class SellerProductCommandController {

    private final SellerProductCommandService sellerProductCommandService;
    private final SellerProductQueryService sellerProductQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerProductCommandController(SellerProductCommandService sellerProductCommandService,
            SellerProductQueryService sellerProductQueryService, SellerActorResolver sellerActorResolver) {
        this.sellerProductCommandService = sellerProductCommandService;
        this.sellerProductQueryService = sellerProductQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 기본정보 수정(전체 치환). 200 + 수정 후 상세. 미존재·타 셀러 404·카테고리 404·검증 400. */
    @PutMapping("/api/v1/seller/products/{productPublicId}")
    public ResponseEntity<SellerProductDetailResponse> update(@PathVariable String productPublicId,
            @Valid @RequestBody SellerProductUpdateRequest body, HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductCommandService.updateBasicInfo(sellerId, productPublicId, body);
        return ResponseEntity.ok(sellerProductQueryService.getProduct(sellerId, productPublicId));
    }

    /** 이미지 메타 전체 치환(URL·유형·순서·대표). 200 + 수정 후 상세. imageId 미존재 404·대표 규칙 위반 400·서버 미발급 URL 400. */
    @PutMapping("/api/v1/seller/products/{productPublicId}/images")
    public ResponseEntity<SellerProductDetailResponse> replaceImages(@PathVariable String productPublicId,
            @Valid @RequestBody SellerProductImagesRequest body, HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductCommandService.replaceImages(sellerId, productPublicId, body);
        return ResponseEntity.ok(sellerProductQueryService.getProduct(sellerId, productPublicId));
    }

    /** variant 메타 수정 + 신규 추가(삭제 없음). 200 + 수정 후 상세. variant 404·옵션 불일치 400·조합 중복 409. */
    @PutMapping("/api/v1/seller/products/{productPublicId}/variants")
    public ResponseEntity<SellerProductDetailResponse> replaceVariants(@PathVariable String productPublicId,
            @Valid @RequestBody SellerProductVariantsRequest body, HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductCommandService.replaceVariants(sellerId, productPublicId, body);
        return ResponseEntity.ok(sellerProductQueryService.getProduct(sellerId, productPublicId));
    }
}

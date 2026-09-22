package com.zslab.mall.product.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.product.controller.request.SellerProductImagesRequest;
import com.zslab.mall.product.controller.request.SellerProductSaleStatusRequest;
import com.zslab.mall.product.controller.request.SellerProductSoldOutRequest;
import com.zslab.mall.product.controller.request.SellerProductUpdateRequest;
import com.zslab.mall.product.controller.request.SellerProductVariantsRequest;
import com.zslab.mall.product.controller.response.SellerProductDetailResponse;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.service.SellerProductCommandService;
import com.zslab.mall.product.service.SellerProductQueryService;
import com.zslab.mall.product.service.SellerProductSaleStatusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 상품 수정 REST 컨트롤러(Track 90-C-2·96-5). 기본정보·이미지·variant PUT 3 endpoint + 판매 상태 전환 POST·수동 품절 PATCH 2 endpoint
 * (D-206·셀러 셀프 판매중지·재판매·품절). 식별자는 public_id({@code prd_}). 조회는 {@link SellerProductQueryController}, 등록은
 * {@link ProductRegistrationController}, 승인·거부·삭제는 관리자 소관이다. URL prefix {@code /api/v1/seller/**}는 SecurityConfig가
 * hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드(D-190·비-GET이라 SUSPENDED 403)는 {@link SellerActorResolver}가 한다. HTTP 책임만
 * 가진다(D-40 β′): 액터 해소·@Valid·Service 위임·수정 후 상세 재조회 응답.
 */
@RestController
public class SellerProductCommandController {

    private final SellerProductCommandService sellerProductCommandService;
    private final SellerProductSaleStatusService sellerProductSaleStatusService;
    private final SellerProductQueryService sellerProductQueryService;
    private final SellerActorResolver sellerActorResolver;
    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final ActorRoleResolver actorRoleResolver;

    public SellerProductCommandController(SellerProductCommandService sellerProductCommandService,
            SellerProductSaleStatusService sellerProductSaleStatusService,
            SellerProductQueryService sellerProductQueryService, SellerActorResolver sellerActorResolver,
            AuthenticatedUserResolver authenticatedUserResolver, ActorRoleResolver actorRoleResolver) {
        this.sellerProductCommandService = sellerProductCommandService;
        this.sellerProductSaleStatusService = sellerProductSaleStatusService;
        this.sellerProductQueryService = sellerProductQueryService;
        this.sellerActorResolver = sellerActorResolver;
        this.authenticatedUserResolver = authenticatedUserResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 현재 인증 셀러 구성원의 감사 컨텍스트(actor_user_id=userId·actor_role=SELLER·계좌 등록 선례). */
    private AuditContext auditContext() {
        return AuditContext.of(authenticatedUserResolver.requireUserId(), actorRoleResolver.requireCoarseRole());
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

    /**
     * 판매 상태 전환(SALE ↔ STOPPED·Track 96-5·D-206). body {@code { status: SALE | STOPPED }}. 200 + 수정 후 상세. 허용 외 값 400·
     * 타 셀러/미존재 404·관리자 중지 재판매 422 PRODUCT_STOPPED_BY_ADMIN·허용 외 전이/같은 상태 422 PRODUCT_INVALID_STATE.
     */
    @PostMapping("/api/v1/seller/products/{productPublicId}/sale-status")
    public ResponseEntity<SellerProductDetailResponse> changeSaleStatus(@PathVariable String productPublicId,
            @Valid @RequestBody SellerProductSaleStatusRequest body, HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductSaleStatusService.changeSaleStatus(sellerId, productPublicId, ProductStatus.valueOf(body.status()), auditContext());
        return ResponseEntity.ok(sellerProductQueryService.getProduct(sellerId, productPublicId));
    }

    /** 상품 단위 수동 품절 on/off(Track 96-5·D3 α). 200 + 수정 후 상세. soldOut 누락 400·타 셀러/미존재 404. */
    @PatchMapping("/api/v1/seller/products/{productPublicId}/soldout")
    public ResponseEntity<SellerProductDetailResponse> changeSoldOut(@PathVariable String productPublicId,
            @Valid @RequestBody SellerProductSoldOutRequest body, HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductSaleStatusService.changeSoldOut(sellerId, productPublicId, body.soldOut(), auditContext());
        return ResponseEntity.ok(sellerProductQueryService.getProduct(sellerId, productPublicId));
    }
}

package com.zslab.mall.product.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.product.controller.request.AddProductImageRequest;
import com.zslab.mall.product.controller.request.ReorderProductImagesRequest;
import com.zslab.mall.product.controller.response.ProductImageResponse;
import com.zslab.mall.product.service.SellerProductImageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller 액터용 상품 이미지 관리 REST 컨트롤러(Track 59 BL-6·seller 주도). 본인 소유 상품의 이미지 등록·대표지정·정렬변경·
 * 삭제를 노출한다. 하위 리소스 특성상 클래스 레벨 base path에 {@code productId}를 두고 메서드는 상대경로를 부여한다.
 *
 * <p>URL prefix {@code /api/v1/seller/}는 SecurityConfig {@code /api/v1/seller/**}→{@code hasRole("SELLER")}가 강제하므로
 * (매처 기존재) 본 트랙은 SecurityConfig를 변경하지 않는다. Seller 식별은 {@link SellerActorResolver}가 SecurityContext에서
 * seller_user 매핑으로 해소한다({@link ProductRegistrationController} 선례).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·요청 검증(@Valid)·Service 위임·상태코드 변환만 수행한다. 소유권(2-hop·404)·
 * 대표 단일성·정렬 집합 검증은 {@link SellerProductImageService} 책임이다.
 */
@RestController
@RequestMapping("/api/v1/seller/products/{productId}/images")
public class SellerProductImageController {

    private final SellerProductImageService sellerProductImageService;
    private final SellerActorResolver sellerActorResolver;

    public SellerProductImageController(
            SellerProductImageService sellerProductImageService, SellerActorResolver sellerActorResolver) {
        this.sellerProductImageService = sellerProductImageService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 이미지 등록. 성공 201 + 생성 이미지. 미인증 401·비-SELLER 403·Bean Validation 위반 400·상품 미소유/미존재 404. */
    @PostMapping
    public ResponseEntity<ProductImageResponse> addImage(
            @PathVariable Long productId,
            @Valid @RequestBody AddProductImageRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sellerProductImageService.add(sellerId, productId, request));
    }

    /** 대표 이미지 지정(멱등). 성공 200. 이미지 미소유/미존재 404. */
    @PatchMapping("/{imageId}/main")
    public ResponseEntity<Void> designateMain(
            @PathVariable Long productId, @PathVariable Long imageId, HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductImageService.designateMain(sellerId, productId, imageId);
        return ResponseEntity.ok().build();
    }

    /** 정렬 순서 재배치. 성공 200. 상품 미소유/미존재 404·imageIds 집합 불일치 400. */
    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable Long productId,
            @Valid @RequestBody ReorderProductImagesRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductImageService.reorder(sellerId, productId, request);
        return ResponseEntity.ok().build();
    }

    /** 이미지 삭제(soft). 성공 204. 이미지 미소유/미존재 404. */
    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> deleteImage(
            @PathVariable Long productId, @PathVariable Long imageId, HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        sellerProductImageService.delete(sellerId, productId, imageId);
        return ResponseEntity.noContent().build();
    }
}

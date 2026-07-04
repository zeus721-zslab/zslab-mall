package com.zslab.mall.product.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.product.controller.request.ProductRegistrationRequest;
import com.zslab.mall.product.controller.response.ProductRegistrationResponse;
import com.zslab.mall.product.service.ProductRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller 액터용 상품 등록 REST 컨트롤러(Track 39 P5·seller 주도). 판매자 자기 상품 등록 1 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link com.zslab.mall.inventory.controller.SellerInventoryController}
 * 선례·D-105 §2 Q2 옵션 A). URL prefix {@code /api/v1/seller/}는 SecurityConfig {@code /api/v1/seller/**}→{@code hasRole("SELLER")}가
 * 강제하므로(매처 기존재) 본 트랙은 SecurityConfig를 변경하지 않는다. Seller 식별은 {@link SellerActorResolver}가
 * SecurityContext에서 seller_user 매핑으로 해소한다(연결 매핑 부재 시 401).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·요청 검증(@Valid)·Service 위임·201 변환만 수행한다. 등록 오케스트레이션·
 * INV-A~D·R5-3·중복 옵션 조합(409)·categoryId(404) 검증은 {@link ProductRegistrationService} 책임이다.
 */
@RestController
public class ProductRegistrationController {

    private final ProductRegistrationService productRegistrationService;
    private final SellerActorResolver sellerActorResolver;

    public ProductRegistrationController(
            ProductRegistrationService productRegistrationService,
            SellerActorResolver sellerActorResolver) {
        this.productRegistrationService = productRegistrationService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /**
     * Seller 주도 상품 등록(Track 39). 성공 201 + product/variant public_id. 미인증 401·비-SELLER 403(SecurityConfig 필터)·
     * Bean Validation 위반 400·INV-A~D·R5-3 위반 400·categoryId 미존재 404·옵션 조합 중복 409({@link ProductRegistrationService}·
     * GlobalExceptionHandler). Product·OptionGroup·OptionValue·Variant·초기 재고를 단일 트랜잭션에 원자 생성한다.
     */
    @PostMapping("/api/v1/seller/products")
    public ResponseEntity<ProductRegistrationResponse> registerProduct(
            @Valid @RequestBody ProductRegistrationRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productRegistrationService.registerProduct(sellerId, request));
    }
}

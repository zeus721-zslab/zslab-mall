package com.zslab.mall.product.controller;

import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.controller.request.ProductCatalogSort;
import com.zslab.mall.product.controller.response.ProductDetailResponse;
import com.zslab.mall.product.controller.response.ProductSummaryResponse;
import com.zslab.mall.product.service.ProductCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매자 상품 카탈로그 조회 REST 컨트롤러(Track 44). 공개 목록·단건 조회 2 endpoint를 노출한다. URL은 액터 중립
 * (/api/v1/products)이며 GET은 SecurityConfig에서 permitAll(공개 카탈로그·D1)이라 인증 없이 접근 가능하다.
 *
 * <p>HTTP 책임만 가진다(D-40·D-43.11): 조회 파라미터 수용·Service 위임·200 변환만 수행하며 Repository 직접 접근·
 * 노출/품절/대표가 판단을 하지 않는다(모두 {@link ProductCatalogService} 책임). 잘못된 sort·page·size·categoryId 타입은
 * {@code MethodArgumentTypeMismatchException}→400(MALFORMED_REQUEST·GlobalExceptionHandler)으로 매핑된다.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductCatalogController {

    private final ProductCatalogService productCatalogService;

    public ProductCatalogController(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    /** 노출대상 상품 목록(D-54 PagedResponse·필터 categoryId·정렬 sort·페이징). 기본 정렬 LATEST·기본 size 20. */
    @GetMapping
    public ResponseEntity<PagedResponse<ProductSummaryResponse>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "LATEST") ProductCatalogSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(productCatalogService.listProducts(categoryId, sort, page, size));
    }

    /** 노출대상 단건 상세. 미존재·비노출은 404(PRODUCT_NOT_FOUND·존재 여부 은닉). */
    @GetMapping("/{productPublicId}")
    public ResponseEntity<ProductDetailResponse> getOne(@PathVariable String productPublicId) {
        return ResponseEntity.ok(productCatalogService.getProduct(productPublicId));
    }
}

package com.zslab.mall.inventory.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.inventory.controller.request.SellerInventoryMarkInboundRequest;
import com.zslab.mall.inventory.controller.request.SellerInventoryMarkOutboundRequest;
import com.zslab.mall.inventory.controller.response.InventoryAdjustResponse;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.repository.ProductVariantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller 액터용 Inventory REST 컨트롤러(Track 27·D-112). 판매자 자기 상품 재고 입고(mark-inbound)·출고(mark-outbound)
 * 2 endpoint를 노출한다. 3P 입점 위탁형 마켓플레이스에서 판매자가 자사 재고를 직접 관리하는 self-service 진입점이다(§9-6 판정).
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다(D-105 §2 Q2 옵션 A·{@link AdminInventoryController} 선례 정합).
 * URL prefix는 {@code /api/v1/seller/inventories/}로 Admin {@code /api/v1/admin/inventories/}와 대칭이다. Seller 식별은
 * {@code X-Seller-Id} 헤더 stub이다(D-92·{@link SellerActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행한다. 소유권(3홉 variantId→
 * productId→sellerId) 검증은 {@link InventoryService} 진입부 책임이므로(D-92 Q3 횡단 원칙) 컨트롤러는 variantPublicId
 * 존재만 404 게이트한다. 미존재·타 seller 소유 모두 {@link ProductVariantNotFoundException}→404로 존재를 은닉한다
 * (기존 Seller 컨트롤러 full-hiding 패턴 정합).
 */
@RestController
public class SellerInventoryController {

    private final InventoryService inventoryService;
    private final ProductVariantRepository productVariantRepository;
    private final SellerActorResolver sellerActorResolver;

    public SellerInventoryController(
            InventoryService inventoryService,
            ProductVariantRepository productVariantRepository,
            SellerActorResolver sellerActorResolver) {
        this.inventoryService = inventoryService;
        this.productVariantRepository = productVariantRepository;
        this.sellerActorResolver = sellerActorResolver;
    }

    /**
     * Seller 재고 입고(D-112). 헤더 누락 401·헤더 형식 오류 400·variantPublicId 미존재/타 seller 404·qty≤0 400·재고
     * 불변조건 위반 422·성공 시 200 + 입고 후 재고 수치 응답. 소유권·수량·불변조건 검증은
     * {@link InventoryService#markInboundBySeller} → {@code Inventory} 도메인 위임 책임이다.
     */
    @PostMapping("/api/v1/seller/inventories/{variantPublicId}/mark-inbound")
    public InventoryAdjustResponse markInbound(
            @PathVariable String variantPublicId,
            @Valid @RequestBody SellerInventoryMarkInboundRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        ProductVariant variant = productVariantRepository.findByPublicId(variantPublicId)
                .orElseThrow(() -> new ProductVariantNotFoundException(
                        "상품 변형을 찾을 수 없습니다: publicId=" + variantPublicId));
        Inventory adjusted = inventoryService.markInboundBySeller(
                sellerId, variant.getId(), request.quantity(), request.reason());
        return InventoryAdjustResponse.from(variantPublicId, adjusted);
    }

    /**
     * Seller 재고 출고(D-112). 헤더 누락 401·헤더 형식 오류 400·variantPublicId 미존재/타 seller 404·qty≤0 400·실물
     * 부족(INV-4) 등 불변조건 위반 422·성공 시 200 + 출고 후 재고 수치 응답. 검증 책임은
     * {@link InventoryService#markOutboundBySeller} → {@code Inventory} 도메인 위임이다.
     */
    @PostMapping("/api/v1/seller/inventories/{variantPublicId}/mark-outbound")
    public InventoryAdjustResponse markOutbound(
            @PathVariable String variantPublicId,
            @Valid @RequestBody SellerInventoryMarkOutboundRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        ProductVariant variant = productVariantRepository.findByPublicId(variantPublicId)
                .orElseThrow(() -> new ProductVariantNotFoundException(
                        "상품 변형을 찾을 수 없습니다: publicId=" + variantPublicId));
        Inventory adjusted = inventoryService.markOutboundBySeller(
                sellerId, variant.getId(), request.quantity(), request.reason());
        return InventoryAdjustResponse.from(variantPublicId, adjusted);
    }
}

package com.zslab.mall.inventory.controller;

import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.inventory.controller.request.AdminInventoryAdjustRequest;
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
 * Admin 액터용 Inventory REST 컨트롤러(Track 21·D-105). 운영자 수동 재고 조정 1 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다(D-105 §2 Q2 옵션 A·{@link com.zslab.mall.delivery.controller.AdminDeliveryController}
 * 선례 정합). Admin 식별은 {@code X-Admin-Id} 헤더 stub이다(D-93·{@link AdminActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행한다. Admin은 전체 접근이므로
 * 권한 검증 단락이 부재하며(D-93 Q3·Q5) {@code resolve} 호출은 헤더 존재·형식 검증만 수행하고 식별자는 사용하지 않는다.
 * variantPublicId 미존재만 404({@link ProductVariantNotFoundException})다.
 */
@RestController
public class AdminInventoryController {

    private final InventoryService inventoryService;
    private final ProductVariantRepository productVariantRepository;
    private final AdminActorResolver adminActorResolver;

    public AdminInventoryController(
            InventoryService inventoryService,
            ProductVariantRepository productVariantRepository,
            AdminActorResolver adminActorResolver) {
        this.inventoryService = inventoryService;
        this.productVariantRepository = productVariantRepository;
        this.adminActorResolver = adminActorResolver;
    }

    /**
     * Admin 재고 조정(D-105 §4). 미존재 variantPublicId → 404. 성공 시 200 + 조정 후 재고 수치 응답.
     * INV-1·INV-4 불변조건·delta=0 가드는 {@link InventoryService#adjustStock} → {@code Inventory} 도메인 위임 책임이다.
     */
    @PostMapping("/api/v1/admin/inventories/{variantPublicId}/adjust")
    public InventoryAdjustResponse adjust(
            @PathVariable String variantPublicId,
            @Valid @RequestBody AdminInventoryAdjustRequest request,
            HttpServletRequest httpRequest) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(httpRequest);
        ProductVariant variant = productVariantRepository.findByPublicId(variantPublicId)
                .orElseThrow(() -> new ProductVariantNotFoundException(
                        "상품 변형을 찾을 수 없습니다: publicId=" + variantPublicId));
        // adjustStock은 @Transactional 종료 후 조정된 Inventory를 반환한다. 스칼라 필드만 읽으므로 OSIV off에서도 재조회 불요.
        Inventory adjusted = inventoryService.adjustStock(variant.getId(), request.quantityDelta(), request.reason());
        return InventoryAdjustResponse.from(variantPublicId, adjusted);
    }
}

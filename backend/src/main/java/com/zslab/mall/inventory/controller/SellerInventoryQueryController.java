package com.zslab.mall.inventory.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.inventory.controller.response.SellerInventorySummaryResponse;
import com.zslab.mall.inventory.service.SellerInventoryQueryService;
import com.zslab.mall.order.controller.response.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 재고 조회 REST 컨트롤러(Track 90-C-1). 자기 상품 variant 축 재고 목록 1 endpoint. 입출고 쓰기는 기존
 * {@link SellerInventoryController}(mark-inbound·mark-outbound)가 담당한다. URL prefix {@code /api/v1/seller/**}는 SecurityConfig가
 * hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드(D-190·GET이라 SUSPENDED 통과)는 {@link SellerActorResolver}가 한다.
 */
@RestController
public class SellerInventoryQueryController {

    private final SellerInventoryQueryService sellerInventoryQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerInventoryQueryController(SellerInventoryQueryService sellerInventoryQueryService,
            SellerActorResolver sellerActorResolver) {
        this.sellerInventoryQueryService = sellerInventoryQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 셀러 재고 목록. keyword(상품명·sellerSku 부분일치)·productPublicId(상품 1건 한정)·page/size(1~100). keyword 50자 초과 400. */
    @GetMapping("/api/v1/seller/inventories")
    public ResponseEntity<PagedResponse<SellerInventorySummaryResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String productPublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerInventoryQueryService.listInventories(sellerId, keyword, productPublicId, page, size));
    }
}

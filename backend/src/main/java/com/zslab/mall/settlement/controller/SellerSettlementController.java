package com.zslab.mall.settlement.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.settlement.controller.response.SellerSettlementDetailResponse;
import com.zslab.mall.settlement.controller.response.SellerSettlementSummaryResponse;
import com.zslab.mall.settlement.controller.response.SettlementItemResponse;
import com.zslab.mall.settlement.enums.SettlementItemType;
import com.zslab.mall.settlement.service.SellerSettlementQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 정산 조회 REST 컨트롤러(Track 85). URL prefix {@code /api/v1/seller/**}는 SecurityConfig가 hasRole(SELLER)로 강제하고,
 * 셀러 식별은 {@link SellerActorResolver}(user.id → seller_user → seller.id)가 한다. 본인·CONFIRMED·PAID만 노출하며 그 외는 404
 * ({@code SellerSettlementQueryService}).
 */
@RestController
public class SellerSettlementController {

    private final SellerSettlementQueryService sellerSettlementQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerSettlementController(SellerSettlementQueryService sellerSettlementQueryService,
            SellerActorResolver sellerActorResolver) {
        this.sellerSettlementQueryService = sellerSettlementQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 본인 정산 목록(CONFIRMED·PAID·최신 기간순). */
    @GetMapping("/api/v1/seller/settlements")
    public ResponseEntity<PagedResponse<SellerSettlementSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerSettlementQueryService.list(sellerId, page, size));
    }

    /** 본인 정산 상세(계좌 끝 4자리). 미존재·타 셀러·PENDING 404. */
    @GetMapping("/api/v1/seller/settlements/{id}")
    public ResponseEntity<SellerSettlementDetailResponse> get(@PathVariable Long id, HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerSettlementQueryService.get(id, sellerId));
    }

    /** 본인 정산 품목(type 선택·occurred_at 오름차순). 미존재·타 셀러·PENDING 404. */
    @GetMapping("/api/v1/seller/settlements/{id}/items")
    public ResponseEntity<PagedResponse<SettlementItemResponse>> items(
            @PathVariable Long id,
            @RequestParam(required = false) SettlementItemType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerSettlementQueryService.listItems(id, sellerId, type, page, size));
    }
}

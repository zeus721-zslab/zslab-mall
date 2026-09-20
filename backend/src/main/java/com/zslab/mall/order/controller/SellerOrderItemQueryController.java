package com.zslab.mall.order.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.controller.response.SellerOrderItemDetailResponse;
import com.zslab.mall.order.controller.response.SellerOrderItemSummaryResponse;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.service.SellerOrderItemQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 품목 조회 REST 컨트롤러(Track 90-B-1). 행 단위는 order_item(셀러 주문 단위 = 자기 품목). URL prefix {@code /api/v1/seller/**}는
 * SecurityConfig가 hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드(D-190·GET이라 SUSPENDED 통과)는 {@link SellerActorResolver}가 한다.
 * 출고는 기존 {@code POST /api/v1/order-items/{oit}/prepare-shipment}({@code SellerShippingController})가 담당한다.
 */
@RestController
public class SellerOrderItemQueryController {

    private final SellerOrderItemQueryService sellerOrderItemQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerOrderItemQueryController(SellerOrderItemQueryService sellerOrderItemQueryService,
            SellerActorResolver sellerActorResolver) {
        this.sellerOrderItemQueryService = sellerOrderItemQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /**
     * 셀러 품목 목록. 필터: status(품목 상태)·from/to(결제일 paid_at·ISO-8601)·keyword(상품명 부분·주문번호 정확). 미결제 주문 품목 제외.
     * 허용 외 enum 400, keyword 50자 초과·from&gt;to 400(MALFORMED_REQUEST).
     */
    @GetMapping("/api/v1/seller/order-items")
    public ResponseEntity<PagedResponse<SellerOrderItemSummaryResponse>> list(
            @RequestParam(required = false) OrderItemStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerOrderItemQueryService.listItems(sellerId, status, from, to, keyword, page, size));
    }

    /** 셀러 품목 상세(배송지 전체). 미존재·타 셀러·미결제 주문 품목 404(존재 은닉). */
    @GetMapping("/api/v1/seller/order-items/{orderItemPublicId}")
    public ResponseEntity<SellerOrderItemDetailResponse> get(@PathVariable String orderItemPublicId, HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerOrderItemQueryService.getItem(sellerId, orderItemPublicId));
    }
}

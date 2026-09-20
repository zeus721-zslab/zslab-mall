package com.zslab.mall.claim.controller;

import com.zslab.mall.claim.controller.response.SellerClaimDetailResponse;
import com.zslab.mall.claim.controller.response.SellerClaimSummaryResponse;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.service.SellerClaimQueryService;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 클레임 조회 REST 컨트롤러(Track 90-D-1·조회 전용). 처리(승인·거부·회수·검수·교환 출고)는 관리자 전용이며
 * ({@code SellerClaimController}는 Track 92에서 제거) 본 클래스는 상태 전이 endpoint를 두지 않는다. URL prefix {@code /api/v1/seller/**}는
 * SecurityConfig가 hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드(D-190·GET이라 SUSPENDED 통과)는 {@link SellerActorResolver}가 한다.
 */
@RestController
public class SellerClaimQueryController {

    private final SellerClaimQueryService sellerClaimQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerClaimQueryController(SellerClaimQueryService sellerClaimQueryService, SellerActorResolver sellerActorResolver) {
        this.sellerClaimQueryService = sellerClaimQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /**
     * 셀러 클레임 목록(자기 품목의 클레임만·요청일 최신순 고정). 필터: type·status·from/to(요청일 requested_at·ISO-8601)·keyword(주문번호
     * 정확·상품명 부분). 허용 외 enum 400, keyword 50자 초과·from&gt;to 400(MALFORMED_REQUEST).
     */
    @GetMapping("/api/v1/seller/claims")
    public ResponseEntity<PagedResponse<SellerClaimSummaryResponse>> list(
            @RequestParam(required = false) ClaimType type,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerClaimQueryService.listClaims(sellerId, type, status, keyword, from, to, page, size));
    }

    /** 셀러 클레임 상세(첨부 목록·교환품 배송 상태). 미존재·타 셀러 품목의 클레임 404(존재 은닉). */
    @GetMapping("/api/v1/seller/claims/{claimPublicId}")
    public ResponseEntity<SellerClaimDetailResponse> get(@PathVariable String claimPublicId, HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerClaimQueryService.getClaim(sellerId, claimPublicId));
    }
}

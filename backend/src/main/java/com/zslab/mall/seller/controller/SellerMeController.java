package com.zslab.mall.seller.controller;

import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.seller.controller.response.SellerMeResponse;
import com.zslab.mall.seller.service.SellerMeQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 본인 조회 REST 컨트롤러(Track 90-B-1). URL prefix {@code /api/v1/seller/**}는 SecurityConfig가 hasRole(SELLER)로 강제하고,
 * 셀러 식별·상태 가드는 {@link SellerActorResolver}(D-190: PENDING·TERMINATED 401·SUSPENDED는 GET이라 통과)가 한다.
 */
@RestController
public class SellerMeController {

    private final SellerMeQueryService sellerMeQueryService;
    private final SellerActorResolver sellerActorResolver;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    public SellerMeController(SellerMeQueryService sellerMeQueryService, SellerActorResolver sellerActorResolver,
            AuthenticatedUserResolver authenticatedUserResolver) {
        this.sellerMeQueryService = sellerMeQueryService;
        this.sellerActorResolver = sellerActorResolver;
        this.authenticatedUserResolver = authenticatedUserResolver;
    }

    /** 본인 셀러·역할·PENDING 정산 건수·주 계좌 등록 여부. SUSPENDED 셀러도 조회 가능(정지 안내 배너의 근거). */
    @GetMapping("/api/v1/seller/me")
    public ResponseEntity<SellerMeResponse> me(HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        Long userId = authenticatedUserResolver.requireUserId();
        return ResponseEntity.ok(sellerMeQueryService.getMe(sellerId, userId));
    }
}

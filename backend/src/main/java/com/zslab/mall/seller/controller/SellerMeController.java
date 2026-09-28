package com.zslab.mall.seller.controller;

import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.seller.controller.response.SellerMeResponse;
import com.zslab.mall.seller.service.SellerMeQueryService;
import com.zslab.mall.user.controller.request.ChangePasswordRequest;
import com.zslab.mall.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final UserService userService;

    public SellerMeController(SellerMeQueryService sellerMeQueryService, SellerActorResolver sellerActorResolver,
            AuthenticatedUserResolver authenticatedUserResolver, UserService userService) {
        this.sellerMeQueryService = sellerMeQueryService;
        this.sellerActorResolver = sellerActorResolver;
        this.authenticatedUserResolver = authenticatedUserResolver;
        this.userService = userService;
    }

    /** 본인 셀러·역할·PENDING 정산 건수·주 계좌 등록 여부. SUSPENDED 셀러도 조회 가능(정지 안내 배너의 근거). */
    @GetMapping("/api/v1/seller/me")
    public ResponseEntity<SellerMeResponse> me(HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        Long userId = authenticatedUserResolver.requireUserId();
        return ResponseEntity.ok(sellerMeQueryService.getMe(sellerId, userId));
    }

    /**
     * 셀러 본인 비밀번호 변경 — PATCH /api/v1/users/me/password의 셀러 접두사 별칭(D-235·역할 쿠키 Path에 실리도록). 원 경로와 같은 서비스를
     * 호출한다. 인가는 원 경로(인증된 모든 역할)보다 좁은 접두사 hasRole SELLER이고, 셀러 상태 가드({@link SellerActorResolver})는 원 경로처럼
     * 거치지 않는다(정지 셀러도 비밀번호는 바꿀 수 있음). 옛 경로는 PR3에서 제거.
     */
    @PatchMapping("/api/v1/seller/me/password")
    public ResponseEntity<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        Long userId = authenticatedUserResolver.requireUserId();
        userService.changePassword(userId, request);
        return ResponseEntity.noContent().build();
    }
}

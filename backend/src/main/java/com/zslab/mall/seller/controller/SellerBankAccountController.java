package com.zslab.mall.seller.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.seller.controller.request.SellerBankAccountRegisterRequest;
import com.zslab.mall.seller.controller.response.SellerBankAccountResponse;
import com.zslab.mall.seller.service.SellerBankAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 본인 정산계좌 REST 컨트롤러(Track 90-D-3·D-199). URL prefix {@code /api/v1/seller/**}는 SecurityConfig가 hasRole(SELLER)로 강제하고,
 * 셀러 식별·상태 가드는 {@link SellerActorResolver}(SUSPENDED 쓰기 403·PENDING/TERMINATED 401)가 한다. SELLER_OWNER 한정은 서비스가 판정한다.
 * 감사 컨텍스트 조립은 {@code SellerDeliveryManagementController} 선례(user.id + coarse role)와 같다.
 */
@RestController
public class SellerBankAccountController {

    private final SellerBankAccountService sellerBankAccountService;
    private final SellerActorResolver sellerActorResolver;
    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final ActorRoleResolver actorRoleResolver;

    public SellerBankAccountController(
            SellerBankAccountService sellerBankAccountService,
            SellerActorResolver sellerActorResolver,
            AuthenticatedUserResolver authenticatedUserResolver,
            ActorRoleResolver actorRoleResolver) {
        this.sellerBankAccountService = sellerBankAccountService;
        this.sellerActorResolver = sellerActorResolver;
        this.authenticatedUserResolver = authenticatedUserResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 본인 셀러 계좌 목록(끝 4자리·주 계좌 표시). 모든 셀러 역할·SUSPENDED 셀러도 조회 가능. */
    @GetMapping("/api/v1/seller/bank-accounts")
    public ResponseEntity<List<SellerBankAccountResponse>> list(HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerBankAccountService.list(sellerId));
    }

    /** 계좌 등록(SELLER_OWNER만·그 외 403 SELLER_OWNER_REQUIRED). 첫 계좌면 자동 주 계좌·VERIFIED. 201 + 계좌 행(끝 4자리). 검증 400. */
    @PostMapping("/api/v1/seller/bank-accounts")
    public ResponseEntity<SellerBankAccountResponse> register(
            @RequestBody @Valid SellerBankAccountRegisterRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        Long userId = authenticatedUserResolver.requireUserId();
        AuditContext auditContext = AuditContext.of(userId, actorRoleResolver.requireCoarseRole());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sellerBankAccountService.register(sellerId, userId, request, auditContext));
    }
}

package com.zslab.mall.seller.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.seller.controller.request.AdminSellerBankAccountPrimaryRequest;
import com.zslab.mall.seller.controller.request.AdminSellerBankAccountRegisterRequest;
import com.zslab.mall.seller.controller.request.AdminSellerBankAccountUpdateRequest;
import com.zslab.mall.seller.controller.response.AdminSellerBankAccountResponse;
import com.zslab.mall.seller.service.AdminSellerBankAccountCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 셀러 정산계좌 REST 컨트롤러(Track 89-F·D-188). 메서드 절대경로({@code AdminSellerController} 선례)·인가는 SecurityConfig
 * {@code /api/v1/admin/**}→ADMIN. HTTP 책임만: 요청 검증·액터 해석·감사 컨텍스트 조립·Service 위임. 응답에는 계좌번호 끝 4자리만 실린다.
 * 계좌 목록은 셀러 상세({@code GET /api/v1/admin/sellers/{slr_}} {@code bankAccounts})가 제공한다.
 */
@RestController
public class AdminSellerBankAccountController {

    private final AdminSellerBankAccountCommandService commandService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminSellerBankAccountController(
            AdminSellerBankAccountCommandService commandService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.commandService = commandService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 계좌 등록. 첫 계좌면 자동 주 계좌·VERIFIED. 201 + 계좌 행(끝 4자리). 셀러 미존재 404·검증 400. */
    @PostMapping("/api/v1/admin/sellers/{sellerPublicId}/bank-accounts")
    public ResponseEntity<AdminSellerBankAccountResponse> register(
            @PathVariable String sellerPublicId,
            @RequestBody @Valid AdminSellerBankAccountRegisterRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commandService.register(sellerPublicId, request, auditContext(httpRequest)));
    }

    /**
     * 계좌 정보 수정(PUT 전체 필드·사유 필수). 정산이 지급 계좌로 참조하는 행은 409 SELLER_BANK_ACCOUNT_REFERENCED·미존재 404·무변경 no-op.
     * 204(FE는 상세 재조회·89-D PUT 선례).
     */
    @PutMapping("/api/v1/admin/sellers/{sellerPublicId}/bank-accounts/{bankAccountId}")
    public ResponseEntity<Void> update(
            @PathVariable String sellerPublicId,
            @PathVariable Long bankAccountId,
            @RequestBody @Valid AdminSellerBankAccountUpdateRequest request,
            HttpServletRequest httpRequest) {
        commandService.update(sellerPublicId, bankAccountId, request, auditContext(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** 주 계좌 전환(사유 필수). 이미 주 계좌 422 SELLER_BANK_ACCOUNT_INVALID_STATE·미존재 404. 204. */
    @PatchMapping("/api/v1/admin/sellers/{sellerPublicId}/bank-accounts/{bankAccountId}/primary")
    public ResponseEntity<Void> changePrimary(
            @PathVariable String sellerPublicId,
            @PathVariable Long bankAccountId,
            @RequestBody @Valid AdminSellerBankAccountPrimaryRequest request,
            HttpServletRequest httpRequest) {
        commandService.changePrimary(sellerPublicId, bankAccountId, request.reason(), auditContext(httpRequest));
        return ResponseEntity.noContent().build();
    }

    private AuditContext auditContext(HttpServletRequest httpRequest) {
        return AuditContext.of(adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
    }
}

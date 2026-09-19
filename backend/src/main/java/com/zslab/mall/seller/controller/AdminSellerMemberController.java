package com.zslab.mall.seller.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.seller.controller.request.AdminSellerMemberAddRequest;
import com.zslab.mall.seller.controller.request.AdminSellerMemberRemoveRequest;
import com.zslab.mall.seller.controller.request.AdminSellerMemberRoleChangeRequest;
import com.zslab.mall.seller.controller.response.AdminSellerDetailResponse;
import com.zslab.mall.seller.service.AdminSellerMemberCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 셀러 구성원 REST 컨트롤러(Track 89-G·D-189). 메서드 절대경로({@code AdminSellerBankAccountController} 선례)·인가는 SecurityConfig
 * {@code /api/v1/admin/**}→ADMIN. HTTP 책임만: 요청 검증·액터 해석·감사 컨텍스트 조립·Service 위임. 구성원 목록은 셀러 상세
 * ({@code GET /api/v1/admin/sellers/{slr_}} {@code members})가 제공하므로 별도 목록 API를 두지 않는다.
 */
@RestController
public class AdminSellerMemberController {

    private final AdminSellerMemberCommandService commandService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminSellerMemberController(
            AdminSellerMemberCommandService commandService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.commandService = commandService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /**
     * 구성원 추가(기존 회원 {@code userPublicId} XOR 신규 계정 {@code newUser}). 201 + 구성원 행. 셀러·회원 미존재 404·탈퇴 회원 409·
     * 이미 소속 409·신규 이메일 중복 409·SMS 실패 502·검증 400(XOR 위반 포함).
     */
    @PostMapping("/api/v1/admin/sellers/{sellerPublicId}/members")
    public ResponseEntity<AdminSellerDetailResponse.Member> add(
            @PathVariable String sellerPublicId,
            @RequestBody @Valid AdminSellerMemberAddRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commandService.add(sellerPublicId, request, auditContext(httpRequest)));
    }

    /** 구성원 제거(사유 필수·DELETE + body·운영자 역할 회수 선례). 마지막 활성 OWNER 409 SELLER_LAST_OWNER·구성원 아님 404. 204. */
    @DeleteMapping("/api/v1/admin/sellers/{sellerPublicId}/members/{userPublicId}")
    public ResponseEntity<Void> remove(
            @PathVariable String sellerPublicId,
            @PathVariable String userPublicId,
            @RequestBody @Valid AdminSellerMemberRemoveRequest request,
            HttpServletRequest httpRequest) {
        commandService.remove(sellerPublicId, userPublicId, request.reason(), auditContext(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** 역할 변경(사유 필수). 같은 역할 422 SELLER_MEMBER_INVALID_STATE·마지막 활성 OWNER 강등 409·구성원 아님 404·오값 400. 204. */
    @PatchMapping("/api/v1/admin/sellers/{sellerPublicId}/members/{userPublicId}/role")
    public ResponseEntity<Void> changeRole(
            @PathVariable String sellerPublicId,
            @PathVariable String userPublicId,
            @RequestBody @Valid AdminSellerMemberRoleChangeRequest request,
            HttpServletRequest httpRequest) {
        commandService.changeRole(sellerPublicId, userPublicId, RoleCode.valueOf(request.role()), request.reason(),
                auditContext(httpRequest));
        return ResponseEntity.noContent().build();
    }

    private AuditContext auditContext(HttpServletRequest httpRequest) {
        return AuditContext.of(adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
    }
}

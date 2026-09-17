package com.zslab.mall.user.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.user.controller.request.AdminMemberGradeRequest;
import com.zslab.mall.user.controller.request.AdminMemberSort;
import com.zslab.mall.user.controller.request.AdminMemberStatusFilter;
import com.zslab.mall.user.controller.request.AdminMemberUpdateRequest;
import com.zslab.mall.user.controller.response.AdminMemberDetailResponse;
import com.zslab.mall.user.controller.response.AdminMemberSummaryResponse;
import com.zslab.mall.user.service.AdminMemberCommandService;
import com.zslab.mall.user.service.AdminMemberQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 관리 REST 컨트롤러(Track 84). 대상은 BUYER role 보유 회원·식별자는 public_id(usr_). 인가는 SecurityConfig
 * {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제한다(AdminOrderController 선례). HTTP 책임만 가진다: 액터 해석·감사 컨텍스트
 * 조립·위임·HTTP 변환. 비대상·미존재는 404, 탈퇴 회원 상태 충돌·진행 중 활동은 409, 연락처 없음 422, SMS 실패 502.
 */
@RestController
@RequestMapping("/api/v1/admin/members")
public class AdminMemberController {

    private final AdminMemberQueryService adminMemberQueryService;
    private final AdminMemberCommandService adminMemberCommandService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminMemberController(AdminMemberQueryService adminMemberQueryService,
            AdminMemberCommandService adminMemberCommandService, AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.adminMemberQueryService = adminMemberQueryService;
        this.adminMemberCommandService = adminMemberCommandService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    private AuditContext auditContext(HttpServletRequest request) {
        return AuditContext.of(adminActorResolver.resolve(request), actorRoleResolver.requireCoarseRole());
    }

    /** 회원 목록. status 기본 ACTIVE·keyword(이름·이메일·연락처 부분일치)·sort 기본 LATEST(가입일 desc). 허용 외 enum 400. */
    @GetMapping
    public ResponseEntity<PagedResponse<AdminMemberSummaryResponse>> list(
            @RequestParam(defaultValue = "ACTIVE") AdminMemberStatusFilter status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "LATEST") AdminMemberSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminMemberQueryService.listMembers(status, keyword, sort, page, size));
    }

    /** 회원 상세(등급·배송지 포함). 미존재·비BUYER 404. */
    @GetMapping("/{publicId}")
    public ResponseEntity<AdminMemberDetailResponse> get(@PathVariable String publicId) {
        return ResponseEntity.ok(adminMemberQueryService.getMember(publicId));
    }

    /** 회원 정보 수정(name·phone). 성공 204·형식 400·탈퇴 회원 409. */
    @PatchMapping("/{publicId}")
    public ResponseEntity<Void> update(@PathVariable String publicId,
            @RequestBody @Valid AdminMemberUpdateRequest body, HttpServletRequest request) {
        adminMemberCommandService.updateMember(publicId, body, auditContext(request));
        return ResponseEntity.noContent().build();
    }

    /** 관리자 탈퇴. 성공 204·이미 탈퇴 409·진행 중 주문/클레임 409. */
    @PostMapping("/{publicId}/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable String publicId, HttpServletRequest request) {
        adminMemberCommandService.withdrawMember(publicId, auditContext(request));
        return ResponseEntity.noContent().build();
    }

    /** 임시 비밀번호 발급·SMS 발송. 성공 204(평문 미노출)·연락처 없음 422·탈퇴 회원 409·발송 실패 502(롤백). */
    @PostMapping("/{publicId}/password-reset")
    public ResponseEntity<Void> resetPassword(@PathVariable String publicId, HttpServletRequest request) {
        adminMemberCommandService.resetPassword(publicId, auditContext(request));
        return ResponseEntity.noContent().build();
    }

    /** 수동 등급 변경(MANUAL·lockedUntil 필수·오늘 이후). 성공 204·검증 실패 400·탈퇴 회원 409. */
    @PutMapping("/{publicId}/grade")
    public ResponseEntity<Void> changeGrade(@PathVariable String publicId,
            @RequestBody @Valid AdminMemberGradeRequest body, HttpServletRequest request) {
        adminMemberCommandService.changeGrade(publicId, body, auditContext(request));
        return ResponseEntity.noContent().build();
    }
}

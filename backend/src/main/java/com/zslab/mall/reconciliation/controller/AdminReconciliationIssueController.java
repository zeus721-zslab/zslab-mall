package com.zslab.mall.reconciliation.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.reconciliation.controller.request.AdminReconciliationIssueResolveRequest;
import com.zslab.mall.reconciliation.controller.response.AdminReconciliationIssueResponse;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import com.zslab.mall.reconciliation.service.AdminReconciliationIssueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 불일치 REST 컨트롤러(Track 104-2 D-216·invariants P6). 목록(상태·유형 필터·페이지)과 해결 처리 2 endpoint. HTTP 책임만 가지며
 * 조회·해결·감사는 {@link AdminReconciliationIssueService}에 위임한다. 권한은 {@code /api/v1/admin/**} ADMIN 매처(SecurityConfig)다.
 *
 * <p><b>응답 코드</b>: 목록 200 · 해결 200 / 메모 누락·500자 초과·잘못된 필터 값 400 / 미존재 404 / 이미 해결 422.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation-issues")
public class AdminReconciliationIssueController {

    private final AdminReconciliationIssueService adminReconciliationIssueService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminReconciliationIssueController(
            AdminReconciliationIssueService adminReconciliationIssueService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.adminReconciliationIssueService = adminReconciliationIssueService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 불일치 목록(최신순). status·type 미지정이면 전체. */
    @GetMapping
    public PagedResponse<AdminReconciliationIssueResponse> list(
            @RequestParam(required = false) ReconciliationIssueStatus status,
            @RequestParam(required = false) ReconciliationIssueType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminReconciliationIssueService.list(status, type, page, size);
    }

    /** 불일치 해결 처리(메모 필수·감사 기록). 업무 데이터는 바꾸지 않는다. */
    @PostMapping("/{issueId}/resolve")
    public AdminReconciliationIssueResponse resolve(
            @PathVariable long issueId,
            @RequestBody @Valid AdminReconciliationIssueResolveRequest request,
            HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(
                adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        return adminReconciliationIssueService.resolve(issueId, request.memo().trim(), auditContext);
    }
}

package com.zslab.mall.review.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.review.controller.request.AdminReviewStatusChangeRequest;
import com.zslab.mall.review.controller.response.AdminReviewResponse;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.service.AdminReviewCommandService;
import com.zslab.mall.review.service.AdminReviewQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 리뷰 REST 컨트롤러(Track 106-1). 인가는 SecurityConfig {@code /api/v1/admin/**}→ADMIN. 숨김·숨김 해제는 감사 대상이라
 * {@link AdminActorResolver}·{@link ActorRoleResolver}로 {@link AuditContext}를 조립해 넘긴다(셀러 상태 전이 선례).
 */
@RestController
@RequestMapping("/api/v1/admin/reviews")
public class AdminReviewController {

    private final AdminReviewQueryService adminReviewQueryService;
    private final AdminReviewCommandService adminReviewCommandService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminReviewController(AdminReviewQueryService adminReviewQueryService, AdminReviewCommandService adminReviewCommandService,
            AdminActorResolver adminActorResolver, ActorRoleResolver actorRoleResolver) {
        this.adminReviewQueryService = adminReviewQueryService;
        this.adminReviewCommandService = adminReviewCommandService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 리뷰 목록(상태 필터 선택·최신순·삭제 제외). status 오값 400. */
    @GetMapping
    public ResponseEntity<PagedResponse<AdminReviewResponse>> list(
            @RequestParam(required = false) ReviewStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(adminReviewQueryService.list(status, page, size));
    }

    /** 숨김·숨김 해제. body {status, reason(필수)} · 204 · 미존재·삭제 404 · 같은 상태 422 · 형식 400. */
    @PatchMapping("/{reviewPublicId}/status")
    public ResponseEntity<Void> changeStatus(@PathVariable String reviewPublicId,
            @RequestBody @Valid AdminReviewStatusChangeRequest request, HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        adminReviewCommandService.changeStatus(reviewPublicId, ReviewStatus.valueOf(request.status()), request.reason(), auditContext);
        return ResponseEntity.noContent().build();
    }
}

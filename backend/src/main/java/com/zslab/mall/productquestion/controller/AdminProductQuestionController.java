package com.zslab.mall.productquestion.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.productquestion.controller.request.AdminProductQuestionStatusChangeRequest;
import com.zslab.mall.productquestion.controller.response.AdminProductQuestionResponse;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import com.zslab.mall.productquestion.service.AdminProductQuestionCommandService;
import com.zslab.mall.productquestion.service.AdminProductQuestionQueryService;
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
 * 관리자 상품 질문 REST 컨트롤러(Track 106-2). 인가는 SecurityConfig {@code /api/v1/admin/**}→ADMIN. 관리자는 숨김·해제만 한다(답변 불가).
 * 숨김·해제는 감사 대상이라 {@link AdminActorResolver}·{@link ActorRoleResolver}로 {@link AuditContext}를 조립해 넘긴다(리뷰 숨김 선례).
 */
@RestController
@RequestMapping("/api/v1/admin/product-questions")
public class AdminProductQuestionController {

    private final AdminProductQuestionQueryService adminProductQuestionQueryService;
    private final AdminProductQuestionCommandService adminProductQuestionCommandService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminProductQuestionController(AdminProductQuestionQueryService adminProductQuestionQueryService,
            AdminProductQuestionCommandService adminProductQuestionCommandService, AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.adminProductQuestionQueryService = adminProductQuestionQueryService;
        this.adminProductQuestionCommandService = adminProductQuestionCommandService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 질문 목록(상태 필터 선택·최신순·삭제 제외). status 오값 400 · size 기본 10(최대 50). */
    @GetMapping
    public ResponseEntity<PagedResponse<AdminProductQuestionResponse>> list(
            @RequestParam(required = false) ProductQuestionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(adminProductQuestionQueryService.list(status, page, size));
    }

    /** 숨김·숨김 해제. body {status, reason(필수)} · 204 · 미존재·삭제 404 · 같은 상태 422 · 형식 400. */
    @PatchMapping("/{questionPublicId}/status")
    public ResponseEntity<Void> changeStatus(@PathVariable String questionPublicId,
            @RequestBody @Valid AdminProductQuestionStatusChangeRequest request, HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        adminProductQuestionCommandService.changeStatus(questionPublicId, ProductQuestionStatus.valueOf(request.status()),
                request.reason(), auditContext);
        return ResponseEntity.noContent().build();
    }
}

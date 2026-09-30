package com.zslab.mall.inquiry.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.inquiry.controller.request.AdminInquiryAnswerRequest;
import com.zslab.mall.inquiry.controller.response.AdminInquiryResponse;
import com.zslab.mall.inquiry.enums.InquiryAnsweredFilter;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import com.zslab.mall.inquiry.service.AdminInquiryCommandService;
import com.zslab.mall.inquiry.service.AdminInquiryQueryService;
import com.zslab.mall.order.controller.response.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 운영자 문의 REST 컨트롤러(Track 106-4). 인가는 SecurityConfig {@code /api/v1/admin/**}→ADMIN. 답변 등록·수정은 감사 대상이라
 * {@link AdminActorResolver}·{@link ActorRoleResolver}로 {@link AuditContext}를 조립해 넘긴다(106-2 관리자 숨김 선례).
 */
@RestController
@RequestMapping("/api/v1/admin/inquiries")
public class AdminInquiryController {

    private final AdminInquiryQueryService adminInquiryQueryService;
    private final AdminInquiryCommandService adminInquiryCommandService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminInquiryController(AdminInquiryQueryService adminInquiryQueryService, AdminInquiryCommandService adminInquiryCommandService,
            AdminActorResolver adminActorResolver, ActorRoleResolver actorRoleResolver) {
        this.adminInquiryQueryService = adminInquiryQueryService;
        this.adminInquiryCommandService = adminInquiryCommandService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 문의 목록. answered ALL·UNANSWERED(기본)·ANSWERED · category 선택(오값 400) · 오래된 순 · size 기본 10(최대 50). */
    @GetMapping
    public ResponseEntity<PagedResponse<AdminInquiryResponse>> list(
            @RequestParam(defaultValue = "UNANSWERED") InquiryAnsweredFilter answered,
            @RequestParam(required = false) InquiryCategory category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(adminInquiryQueryService.list(answered, category, page, size));
    }

    /** 답변 등록·수정(덮어쓰기·수정 시 구매자 미확인 복귀). 204 · 미존재·삭제 404 · 형식 400. */
    @PutMapping("/{inquiryPublicId}/answer")
    public ResponseEntity<Void> answer(@PathVariable String inquiryPublicId, @RequestBody @Valid AdminInquiryAnswerRequest request,
            HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        adminInquiryCommandService.answer(inquiryPublicId, request.content(), auditContext);
        return ResponseEntity.noContent().build();
    }
}

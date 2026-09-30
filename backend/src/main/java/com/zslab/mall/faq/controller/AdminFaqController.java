package com.zslab.mall.faq.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.faq.controller.request.FaqReorderRequest;
import com.zslab.mall.faq.controller.request.FaqWriteRequest;
import com.zslab.mall.faq.controller.response.AdminFaqResponse;
import com.zslab.mall.faq.controller.response.FaqCreatedResponse;
import com.zslab.mall.faq.enums.FaqCategory;
import com.zslab.mall.faq.service.FaqQueryService;
import com.zslab.mall.faq.service.FaqService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 FAQ REST 컨트롤러(Track 106-3 · AdminCategoryController 선례). 인가는 SecurityConfig {@code /api/v1/admin/**}→ADMIN.
 * {@code /order}가 {@code /{faqId}}와 겹치지 않도록 메서드 절대경로를 쓴다(PATCH는 정렬만 있다).
 */
@RestController
public class AdminFaqController {

    private final FaqService faqService;
    private final FaqQueryService faqQueryService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminFaqController(FaqService faqService, FaqQueryService faqQueryService, AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.faqService = faqService;
        this.faqQueryService = faqQueryService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** FAQ 전체(숨김 포함 · 삭제 제외 · 노출 순서). 페이징 없음(소량). */
    @GetMapping("/api/v1/admin/faqs")
    public List<AdminFaqResponse> list() {
        return faqQueryService.listForAdmin();
    }

    /** 등록(해당 카테고리 끝). 201 + id · 검증 실패 400. */
    @PostMapping("/api/v1/admin/faqs")
    public ResponseEntity<FaqCreatedResponse> create(@RequestBody @Valid FaqWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new FaqCreatedResponse(faqService.create(request)));
    }

    /** 수정(PUT 전체 치환 · 카테고리 이동 시 새 카테고리 끝). 204 · 미존재 404 · 검증 실패 400. */
    @PutMapping("/api/v1/admin/faqs/{faqId}")
    public ResponseEntity<Void> update(@PathVariable Long faqId, @RequestBody @Valid FaqWriteRequest request,
            HttpServletRequest httpRequest) {
        faqService.update(faqId, request, auditContext(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** soft-delete. 204 · 미존재·이미 삭제 404. */
    @DeleteMapping("/api/v1/admin/faqs/{faqId}")
    public ResponseEntity<Void> delete(@PathVariable Long faqId, HttpServletRequest httpRequest) {
        faqService.delete(faqId, auditContext(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** 카테고리 안 일괄 정렬(해당 카테고리 전체 id 배열 · index=sortOrder). 누락·중복·다른 카테고리 400. 204. */
    @PatchMapping("/api/v1/admin/faqs/order")
    public ResponseEntity<Void> reorder(@RequestBody @Valid FaqReorderRequest request) {
        faqService.reorder(FaqCategory.valueOf(request.category()), request.faqIds());
        return ResponseEntity.noContent().build();
    }

    private AuditContext auditContext(HttpServletRequest httpRequest) {
        return AuditContext.of(adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
    }
}

package com.zslab.mall.inbox.controller;

import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.inbox.collector.InboxViewer;
import com.zslab.mall.inbox.controller.request.InboxSnoozeKeyRequest;
import com.zslab.mall.inbox.controller.request.InboxSnoozeRequest;
import com.zslab.mall.inbox.controller.response.InboxResponse;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.enums.InboxTab;
import com.zslab.mall.inbox.service.InboxQueryService;
import com.zslab.mall.inbox.service.InboxSnoozeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 운영 인박스(D-248). 인가는 SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제한다. 보류는 호출한 관리자 본인 소유다.
 */
@RestController
@RequestMapping("/api/v1/admin/inbox")
public class AdminInboxController {

    private final InboxQueryService inboxQueryService;
    private final InboxSnoozeService inboxSnoozeService;
    private final AdminActorResolver adminActorResolver;

    public AdminInboxController(InboxQueryService inboxQueryService, InboxSnoozeService inboxSnoozeService,
            AdminActorResolver adminActorResolver) {
        this.inboxQueryService = inboxQueryService;
        this.inboxSnoozeService = inboxSnoozeService;
        this.adminActorResolver = adminActorResolver;
    }

    /** 탭(기본 오늘)의 대기 항목·유형별 건수. type은 관리자 유형만(그 외 400). */
    @GetMapping
    public ResponseEntity<InboxResponse> list(@RequestParam(defaultValue = "TODAY") InboxTab tab,
            @RequestParam(required = false) InboxItemType type, HttpServletRequest request) {
        InboxViewer viewer = InboxViewer.admin(adminActorResolver.resolve(request));
        return ResponseEntity.ok(inboxQueryService.collect(viewer, tab, type));
    }

    /** 보류 생성·갱신. 대상이 지금 인박스 대기 항목이 아니면 404. */
    @PutMapping("/snoozes")
    public ResponseEntity<Void> snooze(@RequestBody @Valid InboxSnoozeRequest body, HttpServletRequest request) {
        inboxSnoozeService.snooze(InboxViewer.admin(adminActorResolver.resolve(request)), body);
        return ResponseEntity.noContent().build();
    }

    /** 보류 해제(본인 보류만·없으면 무시). */
    @DeleteMapping("/snoozes")
    public ResponseEntity<Void> unsnooze(@Valid @ModelAttribute InboxSnoozeKeyRequest key, HttpServletRequest request) {
        inboxSnoozeService.unsnooze(InboxViewer.admin(adminActorResolver.resolve(request)), key.type(), key.ref());
        return ResponseEntity.noContent().build();
    }
}

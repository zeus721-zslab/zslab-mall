package com.zslab.mall.inbox.controller;

import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.common.auth.SellerActorResolver;
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
 * 셀러 운영 인박스(D-248). 인가는 SecurityConfig {@code /api/v1/seller/**}→hasRole(SELLER), 셀러 식별·상태 가드는 {@link SellerActorResolver}
 * (정지 셀러는 조회만·보류 403). 수집은 소속 셀러 소유 항목만이고, 보류는 호출한 구성원 본인 소유다.
 */
@RestController
@RequestMapping("/api/v1/seller/inbox")
public class SellerInboxController {

    private final InboxQueryService inboxQueryService;
    private final InboxSnoozeService inboxSnoozeService;
    private final SellerActorResolver sellerActorResolver;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    public SellerInboxController(InboxQueryService inboxQueryService, InboxSnoozeService inboxSnoozeService,
            SellerActorResolver sellerActorResolver, AuthenticatedUserResolver authenticatedUserResolver) {
        this.inboxQueryService = inboxQueryService;
        this.inboxSnoozeService = inboxSnoozeService;
        this.sellerActorResolver = sellerActorResolver;
        this.authenticatedUserResolver = authenticatedUserResolver;
    }

    /** 탭(기본 오늘)의 대기 항목·유형별 건수. type은 셀러 유형만(그 외 400). */
    @GetMapping
    public ResponseEntity<InboxResponse> list(@RequestParam(defaultValue = "TODAY") InboxTab tab,
            @RequestParam(required = false) InboxItemType type, HttpServletRequest request) {
        return ResponseEntity.ok(inboxQueryService.collect(viewer(request), tab, type));
    }

    /** 보류 생성·갱신. 자기 셀러의 인박스 대기 항목이 아니면 404(타 셀러 항목 존재 은닉). */
    @PutMapping("/snoozes")
    public ResponseEntity<Void> snooze(@RequestBody @Valid InboxSnoozeRequest body, HttpServletRequest request) {
        inboxSnoozeService.snooze(viewer(request), body);
        return ResponseEntity.noContent().build();
    }

    /** 보류 해제(본인 보류만·없으면 무시). */
    @DeleteMapping("/snoozes")
    public ResponseEntity<Void> unsnooze(@Valid @ModelAttribute InboxSnoozeKeyRequest key, HttpServletRequest request) {
        inboxSnoozeService.unsnooze(viewer(request), key.type(), key.ref());
        return ResponseEntity.noContent().build();
    }

    /** 셀러 상태 가드가 먼저 돌도록 resolver를 앞에 호출한다. */
    private InboxViewer viewer(HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return InboxViewer.seller(sellerId, authenticatedUserResolver.requireUserId());
    }
}

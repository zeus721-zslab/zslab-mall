package com.zslab.mall.inbox.controller;

import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.inbox.collector.InboxViewer;
import com.zslab.mall.inbox.controller.request.InboxSnoozeKeyRequest;
import com.zslab.mall.inbox.controller.request.InboxSnoozeRequest;
import com.zslab.mall.inbox.controller.request.SellerDelayNudgeRequest;
import com.zslab.mall.inbox.controller.response.InboxResponse;
import com.zslab.mall.inbox.controller.response.SellerDelayNudgeResponse;
import com.zslab.mall.inbox.controller.response.SellerDelayResponse;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.enums.InboxTab;
import com.zslab.mall.inbox.service.InboxQueryService;
import com.zslab.mall.inbox.service.InboxSnoozeService;
import com.zslab.mall.inbox.service.SellerDelayService;
import com.zslab.mall.inbox.stream.InboxStreamRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 관리자 운영 인박스(D-248). 인가는 SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제한다. 보류는 호출한 관리자 본인 소유다.
 */
@RestController
@RequestMapping("/api/v1/admin/inbox")
public class AdminInboxController {

    private final InboxQueryService inboxQueryService;
    private final InboxSnoozeService inboxSnoozeService;
    private final AdminActorResolver adminActorResolver;
    private final InboxStreamRegistry inboxStreamRegistry;
    private final SellerDelayService sellerDelayService;

    public AdminInboxController(InboxQueryService inboxQueryService, InboxSnoozeService inboxSnoozeService,
            AdminActorResolver adminActorResolver, InboxStreamRegistry inboxStreamRegistry, SellerDelayService sellerDelayService) {
        this.inboxQueryService = inboxQueryService;
        this.inboxSnoozeService = inboxSnoozeService;
        this.adminActorResolver = adminActorResolver;
        this.inboxStreamRegistry = inboxStreamRegistry;
        this.sellerDelayService = sellerDelayService;
    }

    /** 탭(기본 오늘)의 대기 항목·유형별 건수. type은 관리자 유형만(그 외 400). */
    @GetMapping
    public ResponseEntity<InboxResponse> list(@RequestParam(defaultValue = "TODAY") InboxTab tab,
            @RequestParam(required = false) InboxItemType type, HttpServletRequest request) {
        InboxViewer viewer = InboxViewer.admin(adminActorResolver.resolve(request));
        return ResponseEntity.ok(inboxQueryService.collect(viewer, tab, type));
    }

    /** 인박스 변경 신호 스트림(D-249). 내용 없는 "changed" 이벤트를 받으면 목록을 다시 읽는다. 최대 수명이 지나면 서버가 닫고 브라우저가 재연결한다. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream() {
        return ResponseEntity.ok().header(InboxStreamRegistry.NO_BUFFERING_HEADER, "no").body(inboxStreamRegistry.openForAdmin());
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

    /** 셀러 지연 패널 정보(D-252) — 유형별 초과 건수(지금 다시 셈)·마지막 독촉 시각. 없는 셀러 404. */
    @GetMapping("/seller-delays/{sellerPublicId}")
    public ResponseEntity<SellerDelayResponse> sellerDelay(@PathVariable String sellerPublicId) {
        return ResponseEntity.ok(sellerDelayService.get(sellerPublicId));
    }

    /**
     * 셀러 지연 독촉(D-252). 셀러별 결과(SENT·FAILED·NO_RECIPIENT·COOLDOWN·NO_DELAY)와 무관하게 200. 0건·
     * {@value SellerDelayNudgeRequest#MAX_SELLERS}건 초과 400.
     */
    @PostMapping("/seller-delays/nudge")
    public ResponseEntity<SellerDelayNudgeResponse> nudgeSellers(@RequestBody @Valid SellerDelayNudgeRequest body) {
        return ResponseEntity.ok(sellerDelayService.nudge(body.sellerPublicIds()));
    }
}

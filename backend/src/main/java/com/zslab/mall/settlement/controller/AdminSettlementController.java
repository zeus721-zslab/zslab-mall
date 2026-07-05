package com.zslab.mall.settlement.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.settlement.controller.request.CreateMonthlySettlementRequest;
import com.zslab.mall.settlement.controller.response.SettlementBatchResponse;
import com.zslab.mall.settlement.controller.response.SettlementTransitionResponse;
import com.zslab.mall.settlement.entity.Settlement;
import com.zslab.mall.settlement.service.SettlementBatchResult;
import com.zslab.mall.settlement.service.SettlementCreationService;
import com.zslab.mall.settlement.service.SettlementTransitionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 월 정산 배치 REST 컨트롤러(Track 48 P3). 운영자 주도 월 정산 생성 1 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link com.zslab.mall.category.controller.AdminCategoryController}
 * 선례). 인가는 SecurityConfig의 {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제하므로 메서드 @PreAuthorize를
 * 두지 않는다. HTTP 책임만 가진다: 요청 검증·Service 위임·HTTP 변환. 집계·병합·중복 가드는 {@link SettlementCreationService}
 * 책임이다. created_by는 미사용(현행 NULL)이므로 adminId를 주입하지 않는다.
 *
 * <p><b>전이(Track 49)</b>: 월 배치 생성 외에 정산 상태 전이(confirm·pay) 2 endpoint를 노출한다. 전이 로직·비관적 락·멱등은
 * {@link SettlementTransitionService} 책임이며 컨트롤러는 경로변수 위임·HTTP 변환(200)만 한다.
 */
@RestController
public class AdminSettlementController {

    private final SettlementCreationService settlementCreationService;
    private final SettlementTransitionService settlementTransitionService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminSettlementController(SettlementCreationService settlementCreationService,
            SettlementTransitionService settlementTransitionService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.settlementCreationService = settlementCreationService;
        this.settlementTransitionService = settlementTransitionService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 현재 인증 운영자의 감사 컨텍스트를 조립한다(actorId·coarse role·ip/UA 미수집·결정4). */
    private AuditContext auditContext(HttpServletRequest request) {
        return AuditContext.of(adminActorResolver.resolve(request), actorRoleResolver.requireCoarseRole());
    }

    /**
     * 운영자 주도 월 정산 배치 생성(Track 48 P3). 성공 201 + 생성 요약. 기간 범위 위반 400·동시 실행 중복 409
     * ({@link SettlementCreationService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/settlements")
    public ResponseEntity<SettlementBatchResponse> create(
            @RequestBody @Valid CreateMonthlySettlementRequest request) {
        SettlementBatchResult result =
                settlementCreationService.createMonthlySettlements(request.year(), request.month());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SettlementBatchResponse.of(request.year(), request.month(), result));
    }

    /**
     * 정산 금액 확정 전이(PENDING → CONFIRMED·Track 49). 성공 200 + 전이 후 상태. 미존재 404·전이 위반 422
     * ({@link SettlementTransitionService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/settlements/{id}/confirm")
    public ResponseEntity<SettlementTransitionResponse> confirm(@PathVariable Long id, HttpServletRequest request) {
        Settlement settlement = settlementTransitionService.confirm(id, auditContext(request));
        return ResponseEntity.ok(SettlementTransitionResponse.from(settlement));
    }

    /**
     * 정산 지급 완료 마킹 전이(CONFIRMED → PAID·운영자 수동·Track 49). 성공 200 + 전이 후 상태(paid_at 포함). 미존재 404·
     * 전이 위반 422({@link SettlementTransitionService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/settlements/{id}/pay")
    public ResponseEntity<SettlementTransitionResponse> pay(@PathVariable Long id, HttpServletRequest request) {
        Settlement settlement = settlementTransitionService.pay(id, auditContext(request));
        return ResponseEntity.ok(SettlementTransitionResponse.from(settlement));
    }
}

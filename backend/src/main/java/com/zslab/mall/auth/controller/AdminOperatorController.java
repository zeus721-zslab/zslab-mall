package com.zslab.mall.auth.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.auth.controller.request.AdminOperatorProvisioningRequest;
import com.zslab.mall.auth.controller.response.AdminOperatorProvisioningResponse;
import com.zslab.mall.auth.service.AdminOperatorProvisioningService;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 관리자(ADMIN_OPERATOR) 공급 REST 컨트롤러(Track 38·SUPER_ADMIN 주도). 운영 중 관리자 계정 공급 1 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link com.zslab.mall.seller.controller.AdminSellerController}·
 * D-105 §2 Q2 옵션 A 선례). 1차 인가는 SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}(코어스 게이트)가
 * 강제한다. "SUPER_ADMIN만 공급 가능"이라는 세분 인가는 JWT가 SUPER_ADMIN authority를 담지 않으므로 서비스 진입 시
 * user_role 실조회로 검증한다({@link AdminOperatorProvisioningService}·403). 이 세분 인가에 caller userId가 필요하므로
 * AdminSellerController와 달리 {@link AdminActorResolver}로 액터를 해석해 서비스에 전달한다.
 *
 * <p>HTTP 책임만 가진다: 요청 검증·액터 해석·Service 위임·HTTP 변환. 인가·역할 부여·중복 차단은 Service 책임이다.
 */
@RestController
public class AdminOperatorController {

    private final AdminOperatorProvisioningService adminOperatorProvisioningService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminOperatorController(
            AdminOperatorProvisioningService adminOperatorProvisioningService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.adminOperatorProvisioningService = adminOperatorProvisioningService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /**
     * SUPER_ADMIN 주도 운영 관리자 공급(Track 38). 성공 201 + userPublicId. SUPER_ADMIN 아님 403·대상 미존재 404·
     * 중복 부여 409·검증 실패 400({@link AdminOperatorProvisioningService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/admin-operators")
    public ResponseEntity<AdminOperatorProvisioningResponse> provision(
            @RequestBody @Valid AdminOperatorProvisioningRequest request, HttpServletRequest httpRequest) {
        Long callerUserId = adminActorResolver.resolve(httpRequest);
        AuditContext auditContext = AuditContext.of(callerUserId, actorRoleResolver.requireCoarseRole());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminOperatorProvisioningService.provision(callerUserId, request, auditContext));
    }
}

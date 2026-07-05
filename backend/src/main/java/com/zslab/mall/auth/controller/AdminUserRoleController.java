package com.zslab.mall.auth.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.service.RoleRevocationService;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 권한 회수 REST 컨트롤러(Track 53·SUPER_ADMIN 주도·AUTH-4). 대상 회원의 역할 매핑을 회수하는 1 endpoint를 노출한다.
 * ADMIN_OPERATOR 공급({@link AdminOperatorController})과 별개로, 특정 roleCode를 일반적으로 회수하는 user-role
 * 하위 리소스라 단일 책임 컨트롤러로 분리한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link AdminProductController} 선례). 1차 인가는
 * SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}(코어스 게이트)가 강제하고, "SUPER_ADMIN만 회수
 * 가능"·self·last-admin 세분 규칙은 {@link RoleRevocationService}가 담당한다. HTTP 책임만 가진다: 액터 해석·감사
 * 컨텍스트 조립·경로변수 위임·HTTP 변환(204). 식별키는 public_id(usr_)이며 userId 해소는 서비스 책임이다.
 */
@RestController
public class AdminUserRoleController {

    private final RoleRevocationService roleRevocationService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminUserRoleController(
            RoleRevocationService roleRevocationService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.roleRevocationService = roleRevocationService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /**
     * SUPER_ADMIN 주도 권한 회수(Track 53). 성공 204(No Content). SUPER_ADMIN 아님·자기 SUPER_ADMIN 회수 403·
     * 미보유 404·마지막 SUPER_ADMIN 409·roleCode 오값 400({@link RoleRevocationService}·GlobalExceptionHandler).
     */
    @DeleteMapping("/api/v1/admin/users/{userPublicId}/roles/{roleCode}")
    public ResponseEntity<Void> revoke(
            @PathVariable String userPublicId, @PathVariable RoleCode roleCode, HttpServletRequest request) {
        Long callerUserId = adminActorResolver.resolve(request);
        AuditContext auditContext = AuditContext.of(callerUserId, actorRoleResolver.requireCoarseRole());
        roleRevocationService.revoke(callerUserId, userPublicId, roleCode, auditContext);
        return ResponseEntity.noContent().build();
    }
}

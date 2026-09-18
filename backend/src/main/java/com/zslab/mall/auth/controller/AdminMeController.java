package com.zslab.mall.auth.controller;

import com.zslab.mall.auth.controller.response.AdminMeResponse;
import com.zslab.mall.auth.service.AdminOperatorQueryService;
import com.zslab.mall.common.auth.AdminActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 로그인한 관리자 자신 조회 REST 컨트롤러(Track 89-E). JWT가 coarse ADMIN role과 내부 userId만 담아 FE가 SUPER_ADMIN 여부·
 * 자기 publicId를 알 수 없으므로 서버가 알려준다. 운영자 리소스 하위가 아니라 {@code /api/v1/admin/me}에 두어 앞으로 어느
 * 관리자 화면에서든 호출할 수 있게 한다. 인가는 SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제한다.
 * 액터 식별·역할 조회는 auth 도메인 책임이라 {@link AdminOperatorController}와 같은 패키지에 둔다.
 */
@RestController
public class AdminMeController {

    private final AdminOperatorQueryService adminOperatorQueryService;
    private final AdminActorResolver adminActorResolver;

    public AdminMeController(AdminOperatorQueryService adminOperatorQueryService, AdminActorResolver adminActorResolver) {
        this.adminOperatorQueryService = adminOperatorQueryService;
        this.adminActorResolver = adminActorResolver;
    }

    /** 현재 관리자(userPublicId·name·email·roles·superAdmin). 토큰 회원 미존재 404. */
    @GetMapping("/api/v1/admin/me")
    public ResponseEntity<AdminMeResponse> me(HttpServletRequest request) {
        return ResponseEntity.ok(adminOperatorQueryService.me(adminActorResolver.resolve(request)));
    }
}

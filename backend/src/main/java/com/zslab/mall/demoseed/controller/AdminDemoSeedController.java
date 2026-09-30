package com.zslab.mall.demoseed.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.demoseed.controller.response.DemoSeedProductQuestionResponse;
import com.zslab.mall.demoseed.service.DemoProductQuestionSeedService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 데모 데이터 적재 관리자 API(D-244). 1차 인가는 SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}이고, SUPER_ADMIN 전용
 * 세분 인가는 서비스가 user_role 실조회로 강제한다(403 · AdminOperatorController 선례). dryRun 기본값 true — 명시하지 않으면 쓰지 않는다.
 * HTTP 책임만 가진다: 액터 해석·Service 위임.
 */
@RestController
public class AdminDemoSeedController {

    private final DemoProductQuestionSeedService demoProductQuestionSeedService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminDemoSeedController(DemoProductQuestionSeedService demoProductQuestionSeedService, AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.demoProductQuestionSeedService = demoProductQuestionSeedService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 데모 상품 Q&A 부족분 적재. 200 + 계획·결과. SUPER_ADMIN 아님 403. */
    @PostMapping("/api/v1/admin/demo-seed/product-questions")
    public ResponseEntity<DemoSeedProductQuestionResponse> seedProductQuestions(
            @RequestParam(defaultValue = "true") boolean dryRun, HttpServletRequest httpRequest) {
        Long callerUserId = adminActorResolver.resolve(httpRequest);
        AuditContext auditContext = AuditContext.of(callerUserId, actorRoleResolver.requireCoarseRole());
        return ResponseEntity.ok(demoProductQuestionSeedService.seed(callerUserId, dryRun, auditContext));
    }
}

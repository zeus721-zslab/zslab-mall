package com.zslab.mall.product.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.product.controller.response.ProductApprovalResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.service.ProductApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 상품 승인 REST 컨트롤러(Track 50). 운영자가 등록(PENDING) 상품을 승인(→SALE)·거부(→REJECTED)하는 전이
 * 2 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link com.zslab.mall.settlement.controller.AdminSettlementController}
 * 선례). 인가는 SecurityConfig의 {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제하므로 메서드 @PreAuthorize를
 * 두지 않는다. HTTP 책임만 가진다: 경로변수 위임·HTTP 변환(200). 전이 로직·비관적 락·전이 위반 흡수는
 * {@link ProductApprovalService} 책임이다. 식별키는 public_id(prd_)다.
 */
@RestController
public class AdminProductController {

    private final ProductApprovalService productApprovalService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminProductController(ProductApprovalService productApprovalService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.productApprovalService = productApprovalService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 현재 인증 운영자의 감사 컨텍스트를 조립한다(actorId·coarse role·ip/UA 미수집·결정4). */
    private AuditContext auditContext(HttpServletRequest request) {
        return AuditContext.of(adminActorResolver.resolve(request), actorRoleResolver.requireCoarseRole());
    }

    /**
     * 상품 승인 전이(PENDING → SALE·Track 50). 성공 200 + 전이 후 상태. 미존재 404·전이 위반 422
     * ({@link ProductApprovalService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/products/{publicId}/approve")
    public ResponseEntity<ProductApprovalResponse> approve(@PathVariable String publicId, HttpServletRequest request) {
        Product product = productApprovalService.approve(publicId, auditContext(request));
        return ResponseEntity.ok(ProductApprovalResponse.from(product));
    }

    /**
     * 상품 거부 전이(PENDING → REJECTED·Track 50). 성공 200 + 전이 후 상태. 미존재 404·전이 위반 422
     * ({@link ProductApprovalService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/products/{publicId}/reject")
    public ResponseEntity<ProductApprovalResponse> reject(@PathVariable String publicId, HttpServletRequest request) {
        Product product = productApprovalService.reject(publicId, auditContext(request));
        return ResponseEntity.ok(ProductApprovalResponse.from(product));
    }
}

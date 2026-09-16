package com.zslab.mall.seller.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.seller.controller.request.SellerProvisioningRequest;
import com.zslab.mall.seller.controller.response.SellerProvisioningResponse;
import com.zslab.mall.seller.controller.response.SellerSummaryResponse;
import com.zslab.mall.seller.service.SellerProvisioningService;
import com.zslab.mall.seller.service.SellerQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 판매자 REST 컨트롤러(Track 37 입점 + Track 76 선택 목록).
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드 절대경로를 부여한다({@link com.zslab.mall.inventory.controller.AdminInventoryController}
 * 선례·D-105 §2 Q2 옵션 A). 인가는 SecurityConfig의 {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제한다.
 * 입점은 감사 대상 운영자 조작이므로(Track 55·D-139) 감사 actor 식별을 위해 {@link AdminActorResolver}·
 * {@link ActorRoleResolver}로 액터를 해석해 {@code AuditContext}를 조립한 뒤 Service에 전달한다(회수·상품승인·정산·등급
 * 컨트롤러 정합). 입점 로직 자체는 여전히 actor id를 소비하지 않으나, 감사 배선이 actor 컨텍스트를 요구한다.
 *
 * <p>HTTP 책임만 가진다: 요청 검증·액터 해석·감사 컨텍스트 조립·Service 위임·HTTP 변환. 입점 로직·중복 소속 차단·role
 * 배선·감사 적재는 {@link SellerProvisioningService} 책임이다.
 */
@RestController
public class AdminSellerController {

    private final SellerProvisioningService sellerProvisioningService;
    private final SellerQueryService sellerQueryService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminSellerController(
            SellerProvisioningService sellerProvisioningService,
            SellerQueryService sellerQueryService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.sellerProvisioningService = sellerProvisioningService;
        this.sellerQueryService = sellerQueryService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 관리자 셀러 선택 목록(Track 76·상품 등록 폼 드롭다운용·최소 필드·페이징 없음). */
    @GetMapping("/api/v1/admin/sellers")
    public ResponseEntity<List<SellerSummaryResponse>> list() {
        return ResponseEntity.ok(sellerQueryService.listAll());
    }

    /**
     * 관리자 주도 판매자 입점(Track 37). 성공 201 + sellerPublicId. owner 미존재 404·중복 소속 409·status 제한 400·
     * 검증 실패 400({@link SellerProvisioningService}·GlobalExceptionHandler).
     */
    @PostMapping("/api/v1/admin/sellers")
    public ResponseEntity<SellerProvisioningResponse> provision(
            @RequestBody @Valid SellerProvisioningRequest request, HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(
                adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sellerProvisioningService.provision(request, auditContext));
    }
}

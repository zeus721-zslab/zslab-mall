package com.zslab.mall.seller.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.seller.controller.request.AdminSellerStatusChangeRequest;
import com.zslab.mall.seller.controller.request.AdminSellerUpdateRequest;
import com.zslab.mall.seller.controller.request.SellerProvisioningRequest;
import com.zslab.mall.seller.controller.response.AdminSellerDetailResponse;
import com.zslab.mall.seller.controller.response.AdminSellerSummaryResponse;
import com.zslab.mall.seller.controller.response.SellerProvisioningResponse;
import com.zslab.mall.seller.controller.response.SellerSummaryResponse;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.service.AdminSellerCommandService;
import com.zslab.mall.seller.service.AdminSellerQueryService;
import com.zslab.mall.seller.service.SellerProvisioningService;
import com.zslab.mall.seller.service.SellerQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 판매자 REST 컨트롤러(Track 37 입점 + Track 76 선택 목록 + Track 89-D 페이징 목록·상세·상태 전이·정보 수정).
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
    private final AdminSellerQueryService adminSellerQueryService;
    private final AdminSellerCommandService adminSellerCommandService;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminSellerController(
            SellerProvisioningService sellerProvisioningService,
            SellerQueryService sellerQueryService,
            AdminSellerQueryService adminSellerQueryService,
            AdminSellerCommandService adminSellerCommandService,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.sellerProvisioningService = sellerProvisioningService;
        this.sellerQueryService = sellerQueryService;
        this.adminSellerQueryService = adminSellerQueryService;
        this.adminSellerCommandService = adminSellerCommandService;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /** 관리자 셀러 선택 목록(Track 76·상품 등록 폼 드롭다운용·최소 필드·페이징 없음). */
    @GetMapping("/api/v1/admin/sellers")
    public ResponseEntity<List<SellerSummaryResponse>> list() {
        return ResponseEntity.ok(sellerQueryService.listAll());
    }

    /**
     * 관리자 셀러 페이징 목록(Track 89-D). status는 {@link SellerStatus} 직접 바인딩(오값 400)·keyword는 상호·사업자번호·담당자 이메일·
     * 정렬 등록일 desc 고정. 기존 전량 목록({@link #list})은 드롭다운 소비처 호환을 위해 그대로 둔다.
     */
    @GetMapping("/api/v1/admin/sellers/page")
    public ResponseEntity<PagedResponse<AdminSellerSummaryResponse>> page(
            @RequestParam(required = false) SellerStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminSellerQueryService.list(status, keyword, page, size));
    }

    /** 관리자 셀러 상세(Track 89-D·구성원·주 계좌·집계·종료 가능 여부·경고). 미존재 404. */
    @GetMapping("/api/v1/admin/sellers/{sellerPublicId}")
    public ResponseEntity<AdminSellerDetailResponse> get(@PathVariable String sellerPublicId) {
        return ResponseEntity.ok(adminSellerQueryService.get(sellerPublicId));
    }

    /**
     * 셀러 상태 전이(Track 89-D·D-187). body {status, reason}·전이 불가 422 SELLER_INVALID_STATE·종료 가드 409
     * SELLER_ACTIVITY_IN_PROGRESS(blocks)·오값 400. 응답은 전이 후 상세(종료 가능 여부 갱신 반영).
     */
    @PatchMapping("/api/v1/admin/sellers/{sellerPublicId}/status")
    public ResponseEntity<AdminSellerDetailResponse> changeStatus(
            @PathVariable String sellerPublicId,
            @RequestBody @Valid AdminSellerStatusChangeRequest request,
            HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(
                adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        return ResponseEntity.ok(adminSellerCommandService.changeStatus(
                sellerPublicId, SellerStatus.valueOf(request.status()), request.reason(), auditContext));
    }

    /**
     * 셀러 정보 수정(Track 89-D·PUT 전체 필드). 수수료율 변경 시 사유 필수(400 MALFORMED_REQUEST)·사업자번호 중복 409·무변경 no-op.
     * 204(FE는 상세 재조회·89-C 카테고리 수정 선례).
     */
    @PutMapping("/api/v1/admin/sellers/{sellerPublicId}")
    public ResponseEntity<Void> update(
            @PathVariable String sellerPublicId,
            @RequestBody @Valid AdminSellerUpdateRequest request,
            HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(
                adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        adminSellerCommandService.update(sellerPublicId, request, auditContext);
        return ResponseEntity.noContent().build();
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

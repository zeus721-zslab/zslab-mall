package com.zslab.mall.delivery.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.delivery.controller.request.AdminDeliveryScope;
import com.zslab.mall.delivery.controller.request.AdminDeliverySort;
import com.zslab.mall.delivery.controller.request.AdminDeliveryTrackingCorrectionRequest;
import com.zslab.mall.delivery.controller.request.RegisterExchangeShipmentRequest;
import com.zslab.mall.delivery.controller.response.AdminDeliveryDetailResponse;
import com.zslab.mall.delivery.controller.response.AdminDeliverySummaryResponse;
import com.zslab.mall.delivery.controller.response.RegisterExchangeShipmentResponse;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.exception.DeliveryNotFoundException;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.service.AdminDeliveryCommandService;
import com.zslab.mall.delivery.service.AdminDeliveryQueryService;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.order.controller.response.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 Delivery REST 컨트롤러(Track 18·Track 20·D-102·D-104). 교환품 출고 등록·배송 완료 2 endpoint에 Track 89-B(D-184)에서
 * 배송 목록·상세·송장 정정 3 endpoint를 추가했다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드별 절대경로를 부여한다(D-104 §3 옵션 A). 단일 컨트롤러가 서로 다른 두 리소스 축을
 * 노출하기 때문이다:
 * <ul>
 *   <li>{@code POST /api/v1/admin/claims/{claimPublicId}/register-exchange-shipment} — {@code SellerDeliveryController}(Track 92 제거)
 *       URL {@code /api/v1/claims/{claimPublicId}/register-exchange-shipment}과 1:1 대칭·액터축만 admin 치환(D-102 §3 보존).</li>
 *   <li>{@code POST /api/v1/admin/deliveries/{deliveryPublicId}/mark-delivered} — 배송 완료 primitive의 Admin wrapper(D-104).</li>
 *   <li>{@code GET /api/v1/admin/deliveries}·{@code GET .../{deliveryPublicId}}·{@code PATCH .../{deliveryPublicId}/tracking} — Track 89-B.</li>
 * </ul>
 * Admin 식별은 {@code X-Admin-Id} 헤더 stub이다(D-93·{@link AdminActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행한다. Admin은 전체 접근이므로
 * 권한 검증 단락이 부재하며(D-93 Q3·Q5) {@code resolve} 호출은 헤더 존재·형식 검증만 수행하고 식별자는 사용하지 않는다.
 * {@link com.zslab.mall.claim.controller.AdminClaimController} approve/reject Controller 패턴 1:1 재사용이다.
 */
@RestController
public class AdminDeliveryController {

    private final DeliveryService deliveryService;
    private final AdminDeliveryQueryService adminDeliveryQueryService;
    private final AdminDeliveryCommandService adminDeliveryCommandService;
    private final ClaimRepository claimRepository;
    private final DeliveryRepository deliveryRepository;
    private final AdminActorResolver adminActorResolver;
    private final ActorRoleResolver actorRoleResolver;

    public AdminDeliveryController(
            DeliveryService deliveryService,
            AdminDeliveryQueryService adminDeliveryQueryService,
            AdminDeliveryCommandService adminDeliveryCommandService,
            ClaimRepository claimRepository,
            DeliveryRepository deliveryRepository,
            AdminActorResolver adminActorResolver,
            ActorRoleResolver actorRoleResolver) {
        this.deliveryService = deliveryService;
        this.adminDeliveryQueryService = adminDeliveryQueryService;
        this.adminDeliveryCommandService = adminDeliveryCommandService;
        this.claimRepository = claimRepository;
        this.deliveryRepository = deliveryRepository;
        this.adminActorResolver = adminActorResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /**
     * 관리자 배송 목록(Track 89-B D-184). 배송 행 단위. 필터: scope(ORIGINAL 기본·CLAIM_OUTBOUND·RETURN·ALL)·status·carrier·
     * keyword(송장번호 정확·주문번호 정확·수령인명 부분)·from/to(발송일 shipped_at·ISO-8601). 허용 외 enum·sort 값 400,
     * keyword 50자 초과·from&gt;to 400(MALFORMED_REQUEST). 인가는 SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제한다.
     */
    @GetMapping("/api/v1/admin/deliveries")
    public PagedResponse<AdminDeliverySummaryResponse> list(
            @RequestParam(defaultValue = "ORIGINAL") AdminDeliveryScope scope,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) DeliveryCarrier carrier,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "LATEST") AdminDeliverySort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminDeliveryQueryService.listDeliveries(scope, status, carrier, keyword, from, to, sort, page, size);
    }

    /** 관리자 배송 상세(Track 89-B D-184). 미존재 deliveryPublicId → 404. */
    @GetMapping("/api/v1/admin/deliveries/{deliveryPublicId}")
    public AdminDeliveryDetailResponse get(@PathVariable String deliveryPublicId) {
        return adminDeliveryQueryService.getDelivery(deliveryPublicId);
    }

    /**
     * 관리자 송장 정정(Track 89-B D-184). SHIPPING에서만 허용(그 외 422 DELIVERY_INVALID_STATE)·타 배송과 송장번호 중복 409·
     * 사유 누락/200자 초과·carrier 누락 400. 상태는 바꾸지 않으며 값이 바뀐 경우에만 감사 로그를 남긴다. 미존재 404.
     */
    @PatchMapping("/api/v1/admin/deliveries/{deliveryPublicId}/tracking")
    public RegisterExchangeShipmentResponse correctTracking(
            @PathVariable String deliveryPublicId,
            @Valid @RequestBody AdminDeliveryTrackingCorrectionRequest request,
            HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(
                adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        // 서비스는 @Transactional 종료 후 정정 반영 Delivery를 반환한다. 스칼라만 읽으므로 OSIV off에서도 재조회 불요.
        Delivery corrected = adminDeliveryCommandService.correctTracking(
                deliveryPublicId, request.carrier(), request.trackingNo(), request.reason(), auditContext);
        return RegisterExchangeShipmentResponse.from(corrected);
    }

    /**
     * Admin 교환품 출고 등록(D-102 §3·§5). 미존재 claimPublicId → 404. 성공 시 200 + 출고된 Delivery 응답.
     * type/이중 호출 멱등 가드는 {@link DeliveryService#registerExchangeShipmentByAdmin} primitive 위임 책임이다.
     */
    @PostMapping("/api/v1/admin/claims/{claimPublicId}/register-exchange-shipment")
    public RegisterExchangeShipmentResponse registerExchangeShipment(
            @PathVariable String claimPublicId,
            @Valid @RequestBody RegisterExchangeShipmentRequest request,
            HttpServletRequest httpRequest) {
        AuditContext auditContext = AuditContext.of(
                adminActorResolver.resolve(httpRequest), actorRoleResolver.requireCoarseRole());
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        Delivery delivery = deliveryService.registerExchangeShipmentByAdmin(
                claim.getId(), request.carrier(), request.trackingNo(), auditContext);
        return RegisterExchangeShipmentResponse.from(delivery);
    }

    /**
     * Admin 배송 완료 처리(D-104 §4). 미존재 deliveryPublicId → 404. 성공 시 200 + 배송 완료된 Delivery 응답.
     * 상태 전이 합법성·DLV-3 검증은 {@link DeliveryService#markDeliveredByAdmin} → primitive 위임 책임이다.
     */
    @PostMapping("/api/v1/admin/deliveries/{deliveryPublicId}/mark-delivered")
    public RegisterExchangeShipmentResponse markDelivered(
            @PathVariable String deliveryPublicId,
            HttpServletRequest httpRequest) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(httpRequest);
        Delivery delivery = deliveryRepository.findByPublicId(deliveryPublicId)
                .orElseThrow(() -> new DeliveryNotFoundException("배송을 찾을 수 없습니다: publicId=" + deliveryPublicId));
        deliveryService.markDeliveredByAdmin(delivery.getId());
        // OSIV off·wrapper 트랜잭션 종료 후 첫 조회 엔티티는 stale(SHIPPING) → 재조회로 DELIVERED 반영(AdminClaimController.toResponse 패턴).
        return deliveryRepository.findByPublicId(deliveryPublicId)
                .map(RegisterExchangeShipmentResponse::from)
                .orElseThrow(() -> new IllegalStateException("Delivery 전이 후 재조회 실패: publicId=" + deliveryPublicId));
    }
}

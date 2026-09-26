package com.zslab.mall.delivery.controller;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.common.auth.ActorRoleResolver;
import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.delivery.controller.request.AdminDeliveryScope;
import com.zslab.mall.delivery.controller.request.AdminDeliverySort;
import com.zslab.mall.delivery.controller.request.SellerDeliveryTrackingCorrectionRequest;
import com.zslab.mall.delivery.controller.response.SellerDeliverySummaryResponse;
import com.zslab.mall.delivery.controller.response.SellerDeliveryTrackingCorrectionResponse;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.delivery.service.SellerDeliveryCommandService;
import com.zslab.mall.delivery.service.SellerDeliveryQueryService;
import com.zslab.mall.order.controller.response.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 배송 목록·송장 정정 REST 컨트롤러(Track 90-B-1). 기존 {@code SellerDeliveryController}(Track 92 제거·{@code /api/v1/claims/**} 교환품 출고)·
 * {@code SellerDeliveryCompletionController}({@code /api/v1/deliveries/**} 배송 완료)와 URL 축이 달라 별도 클래스로 둔다.
 * URL prefix {@code /api/v1/seller/**}는 SecurityConfig가 hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드는 {@link SellerActorResolver}가
 * 한다(D-190: GET은 SUSPENDED 통과·PATCH는 SUSPENDED 403).
 */
@RestController
public class SellerDeliveryManagementController {

    private final SellerDeliveryQueryService sellerDeliveryQueryService;
    private final SellerDeliveryCommandService sellerDeliveryCommandService;
    private final SellerActorResolver sellerActorResolver;
    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final ActorRoleResolver actorRoleResolver;

    public SellerDeliveryManagementController(SellerDeliveryQueryService sellerDeliveryQueryService,
            SellerDeliveryCommandService sellerDeliveryCommandService, SellerActorResolver sellerActorResolver,
            AuthenticatedUserResolver authenticatedUserResolver, ActorRoleResolver actorRoleResolver) {
        this.sellerDeliveryQueryService = sellerDeliveryQueryService;
        this.sellerDeliveryCommandService = sellerDeliveryCommandService;
        this.sellerActorResolver = sellerActorResolver;
        this.authenticatedUserResolver = authenticatedUserResolver;
        this.actorRoleResolver = actorRoleResolver;
    }

    /**
     * 셀러 배송 목록(자기 품목의 배송 행만). 필터·정렬은 관리자와 동일: scope(ORIGINAL 기본·CLAIM_OUTBOUND·RETURN·ALL)·status·carrier·
     * keyword(송장번호 정확·주문번호 정확·수령인명 부분)·from/to(발송일 shipped_at·ISO-8601). 허용 외 enum·sort 값 400,
     * keyword 50자 초과·from&gt;to 400(MALFORMED_REQUEST).
     */
    @GetMapping("/api/v1/seller/deliveries")
    public PagedResponse<SellerDeliverySummaryResponse> list(
            @RequestParam(defaultValue = "ORIGINAL") AdminDeliveryScope scope,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) DeliveryCarrier carrier,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "LATEST") AdminDeliverySort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return sellerDeliveryQueryService.listDeliveries(sellerId, scope, status, carrier, keyword, from, to, sort, page, size);
    }

    /**
     * 셀러 송장 정정(관리자 {@code PATCH /api/v1/admin/deliveries/{id}/tracking}와 동일 제약). SHIPPING에서만(그 외 422 DELIVERY_INVALID_STATE)·
     * 타 배송과 같은 송장번호 허용(D-227)·송장 형식 위반·사유 누락/200자 초과·carrier 누락 400. 미존재·타 셀러 배송 404(존재 은닉). SUSPENDED 셀러 403(resolver).
     */
    @PatchMapping("/api/v1/seller/deliveries/{deliveryPublicId}/tracking")
    public SellerDeliveryTrackingCorrectionResponse correctTracking(
            @PathVariable String deliveryPublicId,
            @Valid @RequestBody SellerDeliveryTrackingCorrectionRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        AuditContext auditContext = AuditContext.of(authenticatedUserResolver.requireUserId(), actorRoleResolver.requireCoarseRole());
        // 서비스는 @Transactional 종료 후 정정 반영 Delivery를 반환한다. 스칼라만 읽으므로 OSIV off에서도 재조회 불요(관리자 선례).
        Delivery corrected = sellerDeliveryCommandService.correctTracking(
                sellerId, deliveryPublicId, request.carrier(), request.trackingNo(), request.reason(), auditContext);
        return SellerDeliveryTrackingCorrectionResponse.from(corrected);
    }
}

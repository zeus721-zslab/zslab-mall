package com.zslab.mall.delivery.controller;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.delivery.controller.request.RegisterExchangeShipmentRequest;
import com.zslab.mall.delivery.controller.response.RegisterExchangeShipmentResponse;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.service.DeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 교환품 출고 등록 REST 컨트롤러(Track 18·D-102 §3 β). EXCHANGE 출고 등록 1 endpoint를 노출한다.
 *
 * <p>base path {@code /api/v1/admin/claims}는 {@link com.zslab.mall.claim.controller.AdminClaimController}와 공존하며
 * 하위 경로 {@code /register-exchange-shipment}로 분리한다. {@link SellerDeliveryController} URL
 * {@code /api/v1/claims/{claimPublicId}/register-exchange-shipment}과 1:1 대칭이며 액터축만 admin으로 치환한다(D-102 §3).
 * Admin 식별은 {@code X-Admin-Id} 헤더 stub이다(D-93·{@link AdminActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행한다. Admin은 전체 접근이므로
 * 권한 검증 단락이 부재하며(D-93 Q3·Q5) {@code resolve} 호출은 헤더 존재·형식 검증만 수행하고 식별자는 사용하지 않는다.
 * {@link com.zslab.mall.claim.controller.AdminClaimController} approve/reject Controller 패턴 1:1 재사용이다.
 */
@RestController
@RequestMapping("/api/v1/admin/claims")
public class AdminDeliveryController {

    private final DeliveryService deliveryService;
    private final ClaimRepository claimRepository;
    private final AdminActorResolver adminActorResolver;

    public AdminDeliveryController(
            DeliveryService deliveryService,
            ClaimRepository claimRepository,
            AdminActorResolver adminActorResolver) {
        this.deliveryService = deliveryService;
        this.claimRepository = claimRepository;
        this.adminActorResolver = adminActorResolver;
    }

    /**
     * Admin 교환품 출고 등록(D-102 §3·§5). 미존재 claimPublicId → 404. 성공 시 200 + 출고된 Delivery 응답.
     * type/이중 호출 멱등 가드는 {@link DeliveryService#registerExchangeShipmentByAdmin} primitive 위임 책임이다.
     */
    @PostMapping("/{claimPublicId}/register-exchange-shipment")
    public RegisterExchangeShipmentResponse registerExchangeShipment(
            @PathVariable String claimPublicId,
            @Valid @RequestBody RegisterExchangeShipmentRequest request,
            HttpServletRequest httpRequest) {
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(httpRequest);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        Delivery delivery = deliveryService.registerExchangeShipmentByAdmin(
                claim.getId(), request.carrier(), request.trackingNo());
        return RegisterExchangeShipmentResponse.from(delivery);
    }
}

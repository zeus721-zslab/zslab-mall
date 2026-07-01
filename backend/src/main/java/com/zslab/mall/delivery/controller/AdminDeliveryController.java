package com.zslab.mall.delivery.controller;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.auth.AdminActorResolver;
import com.zslab.mall.delivery.controller.request.RegisterExchangeShipmentRequest;
import com.zslab.mall.delivery.controller.response.RegisterExchangeShipmentResponse;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.exception.DeliveryNotFoundException;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.service.DeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 Delivery REST 컨트롤러(Track 18·Track 20·D-102·D-104). 교환품 출고 등록·배송 완료 2 endpoint를 노출한다.
 *
 * <p>클래스 레벨 base path를 두지 않고 메서드별 절대경로를 부여한다(D-104 §3 옵션 A). 단일 컨트롤러가 서로 다른 두 리소스 축을
 * 노출하기 때문이다:
 * <ul>
 *   <li>{@code POST /api/v1/admin/claims/{claimPublicId}/register-exchange-shipment} — {@link SellerDeliveryController}
 *       URL {@code /api/v1/claims/{claimPublicId}/register-exchange-shipment}과 1:1 대칭·액터축만 admin 치환(D-102 §3 보존).</li>
 *   <li>{@code POST /api/v1/admin/deliveries/{deliveryPublicId}/mark-delivered} — 배송 완료 primitive의 Admin wrapper(D-104).</li>
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
    private final ClaimRepository claimRepository;
    private final DeliveryRepository deliveryRepository;
    private final AdminActorResolver adminActorResolver;

    public AdminDeliveryController(
            DeliveryService deliveryService,
            ClaimRepository claimRepository,
            DeliveryRepository deliveryRepository,
            AdminActorResolver adminActorResolver) {
        this.deliveryService = deliveryService;
        this.claimRepository = claimRepository;
        this.deliveryRepository = deliveryRepository;
        this.adminActorResolver = adminActorResolver;
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
        // X-Admin-Id 존재·형식 검증만 수행한다(전체 접근·식별자 미사용·D-93 Q3). 누락 401·형식 오류 400.
        adminActorResolver.resolve(httpRequest);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        Delivery delivery = deliveryService.registerExchangeShipmentByAdmin(
                claim.getId(), request.carrier(), request.trackingNo());
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

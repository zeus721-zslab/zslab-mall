package com.zslab.mall.delivery.controller;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.delivery.controller.request.RegisterExchangeShipmentRequest;
import com.zslab.mall.delivery.controller.response.RegisterExchangeShipmentResponse;
import com.zslab.mall.delivery.entity.Delivery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller 액터용 교환품 출고 등록 REST 컨트롤러(D-99). EXCHANGE 출고 등록 1 endpoint를 노출한다(D-99 Q1·Q2 α).
 *
 * <p>base path {@code /api/v1/claims}는 {@link com.zslab.mall.claim.controller.SellerClaimController}와 공존하며
 * 하위 경로 {@code /register-exchange-shipment}로 분리한다(D-99 Q1). Seller 식별은 {@code X-Seller-Id} 헤더 stub이다
 * (D-93·{@link SellerActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′·D-99 Q5 α): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행하며 권한/상태 판단은
 * {@link ClaimService} 책임이다. {@link com.zslab.mall.claim.controller.SellerClaimController} L19-27 패턴 1:1 재사용.
 */
@RestController
@RequestMapping("/api/v1/claims")
public class SellerDeliveryController {

    private final ClaimService claimService;
    private final ClaimRepository claimRepository;
    private final SellerActorResolver sellerActorResolver;

    public SellerDeliveryController(
            ClaimService claimService,
            ClaimRepository claimRepository,
            SellerActorResolver sellerActorResolver) {
        this.claimService = claimService;
        this.claimRepository = claimRepository;
        this.sellerActorResolver = sellerActorResolver;
    }

    /**
     * Seller 교환품 출고 등록(D-99 Q9 γ). 미존재·권한 위반 모두 404(정보 노출 회피·D-99 Q10). 성공 시 200 + 출고된 Delivery 응답.
     * 권한 검증·이중 호출 멱등 가드는 {@link ClaimService#registerExchangeShipmentBySeller}·DeliveryService 책임이다.
     */
    @PostMapping("/{claimPublicId}/register-exchange-shipment")
    public RegisterExchangeShipmentResponse registerExchangeShipment(
            @PathVariable String claimPublicId,
            @Valid @RequestBody RegisterExchangeShipmentRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        Claim claim = claimRepository.findByPublicId(claimPublicId)
                .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: publicId=" + claimPublicId));
        Delivery delivery = claimService.registerExchangeShipmentBySeller(
                claim.getId(), sellerId, request.carrier(), request.trackingNo());
        return RegisterExchangeShipmentResponse.from(delivery);
    }
}

package com.zslab.mall.order.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.delivery.controller.response.RegisterExchangeShipmentResponse;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.exception.DeliveryNotFoundException;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.service.OrderShippingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller 액터용 일반 주문 배송 완료 REST 컨트롤러(Track 43·M1). 판매자 수동 배송 완료(mark-delivered) 1 endpoint를 노출한다.
 *
 * <p><b>경로·패키지</b>: {@code POST /api/v1/deliveries/{deliveryPublicId}/mark-delivered}는 Admin
 * {@code /api/v1/admin/deliveries/{deliveryPublicId}/mark-delivered}({@code AdminDeliveryController})와 액터축만 다른 대칭이다.
 * 배송 완료의 소유권 해소(delivery→orderItem→seller)가 order 패키지 책임이라 본 컨트롤러는 delivery 패키지가 아닌 order 패키지에 둔다
 * (delivery는 order 무지 유지·{@code SellerShippingController} 패키지 정합).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행하며 소유권/상태 판단은
 * {@link OrderShippingService#markDeliveredBySeller} 책임이다. Seller 식별은 {@link SellerActorResolver}가 SecurityContext로
 * 해소한다(인증 없으면 401). publicId 미존재는 404로 게이트한다(미존재·타 seller 모두 {@link DeliveryNotFoundException}→404·존재 은닉).
 */
@RestController
public class SellerDeliveryCompletionController {

    private final OrderShippingService orderShippingService;
    private final DeliveryRepository deliveryRepository;
    private final SellerActorResolver sellerActorResolver;

    public SellerDeliveryCompletionController(
            OrderShippingService orderShippingService,
            DeliveryRepository deliveryRepository,
            SellerActorResolver sellerActorResolver) {
        this.orderShippingService = orderShippingService;
        this.deliveryRepository = deliveryRepository;
        this.sellerActorResolver = sellerActorResolver;
    }

    /**
     * Seller 일반 주문 배송 완료. 헤더 누락 401·Delivery 미존재/타 seller 404·비-SHIPPING 상태 422·성공 시 200 + 배송 완료된 Delivery 응답.
     * 소유권·상태 검증은 {@link OrderShippingService#markDeliveredBySeller} 책임이다({@code AdminDeliveryController.markDelivered} 대칭).
     */
    @PostMapping("/api/v1/deliveries/{deliveryPublicId}/mark-delivered")
    public RegisterExchangeShipmentResponse markDelivered(
            @PathVariable String deliveryPublicId,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        Delivery delivery = deliveryRepository.findByPublicId(deliveryPublicId)
                .orElseThrow(() -> new DeliveryNotFoundException("배송을 찾을 수 없습니다: publicId=" + deliveryPublicId));
        orderShippingService.markDeliveredBySeller(sellerId, delivery.getId());
        // OSIV off·서비스 트랜잭션 종료 후 첫 조회 엔티티는 stale(SHIPPING) → 재조회로 DELIVERED 반영(AdminDeliveryController.markDelivered 패턴).
        return deliveryRepository.findByPublicId(deliveryPublicId)
                .map(RegisterExchangeShipmentResponse::from)
                .orElseThrow(() -> new IllegalStateException("Delivery 전이 후 재조회 실패: publicId=" + deliveryPublicId));
    }
}

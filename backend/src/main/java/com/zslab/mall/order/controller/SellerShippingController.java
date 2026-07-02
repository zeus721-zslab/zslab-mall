package com.zslab.mall.order.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.order.controller.request.PrepareShipmentRequest;
import com.zslab.mall.order.controller.response.PrepareShipmentResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderShippingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller 액터용 일반 주문 배송 개시 REST 컨트롤러(Track 23). 판매자 수동 출고(prepare-shipment) 1 endpoint를 노출한다.
 *
 * <p>base path는 {@code /api/v1/order-items}로 신설한다({@code /api/v1/claims} 재사용 금지·EXCHANGE 의미 오염 회피).
 * OrderItem은 전역 유일한 public_id(oit_)로 식별한다. Seller 식별은 {@code X-Seller-Id} 헤더 stub이다(D-93·{@link SellerActorResolver}).
 *
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행하며 권한/상태 판단은
 * {@link OrderShippingService} 책임이다({@code SellerDeliveryController} 패턴 1:1). 소유권 검증은 Service 진입부가 수행하므로
 * 컨트롤러는 publicId 존재만 404 게이트한다(미존재·타 seller 모두 {@link OrderNotFoundException}→404·존재 은닉).
 */
@RestController
public class SellerShippingController {

    private final OrderShippingService orderShippingService;
    private final OrderItemRepository orderItemRepository;
    private final SellerActorResolver sellerActorResolver;

    public SellerShippingController(
            OrderShippingService orderShippingService,
            OrderItemRepository orderItemRepository,
            SellerActorResolver sellerActorResolver) {
        this.orderShippingService = orderShippingService;
        this.orderItemRepository = orderItemRepository;
        this.sellerActorResolver = sellerActorResolver;
    }

    /**
     * Seller 일반 주문 배송 개시. 헤더 누락 401·OrderItem 미존재/타 seller 404·비-PAID 상태 422·성공 시 200 + 발송된 Delivery 응답.
     * 소유권·상태 검증은 {@link OrderShippingService#prepareShipment} 책임이다.
     */
    @PostMapping("/api/v1/order-items/{orderItemPublicId}/prepare-shipment")
    public PrepareShipmentResponse prepareShipment(
            @PathVariable String orderItemPublicId,
            @Valid @RequestBody PrepareShipmentRequest request,
            HttpServletRequest httpRequest) {
        Long sellerId = sellerActorResolver.resolve(httpRequest);
        OrderItem orderItem = orderItemRepository.findByPublicId(orderItemPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문 품목을 찾을 수 없습니다: publicId=" + orderItemPublicId));
        Delivery delivery = orderShippingService.prepareShipment(
                sellerId, orderItem.getId(), request.carrier(), request.trackingNo());
        return PrepareShipmentResponse.from(delivery);
    }
}

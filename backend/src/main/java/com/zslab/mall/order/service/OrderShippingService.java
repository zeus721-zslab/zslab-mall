package com.zslab.mall.order.service;

import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.exception.DeliveryInvalidStateException;
import com.zslab.mall.delivery.exception.DeliveryNotFoundException;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.delivery.service.DeliveryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 주문 배송 개시 Application Service(Track 23·S급). 판매자 수동 출고(prepare-shipment) façade로
 * 단일 트랜잭션에서 OrderItem PAID→PREPARING 전이 후 Delivery 생성·발송을 오케스트레이션한다.
 *
 * <p><b>PREPARING 주체 = Order Aggregate</b>: OrderItem 상태 전이는 본 서비스(order 패키지)가 책임지고 Delivery 생성은
 * {@link DeliveryService#createForOrder}에 위임한다(order→delivery 단방향 의존·delivery는 order 무지 유지).
 *
 * <p><b>가드 A(@Transactional)</b>: 권한→PREPARING→create→markShipping 4단계를 단일 트랜잭션·단일 영속성 컨텍스트로 묶는다.
 * 하위 {@link DeliveryService} 메서드는 REQUIRED로 join한다. markShipping의 E4 동기 소비({@code DeliveryStartedHandler})가
 * 같은 트랜잭션·같은 영속성 컨텍스트에서 방금 PREPARING으로 전이된 OrderItem을 SHIPPING으로 확정한다.
 *
 * <p><b>가드 B(순서)</b>: {@link #changeToPreparing}는 반드시 markShipping(E4 발행) 前 실행한다. 역전 시 OrderItem이 PAID인 채
 * E4가 발화하여 {@code DeliveryStartedHandler} 가드가 warn+return(조용한 skip)으로 OrderItem을 PAID로 잔류시켜
 * Delivery만 SHIPPING이 되는 데이터 불일치가 발생한다.
 *
 * <p><b>배송 완료(Track 43)</b>: {@link #markDeliveredBySeller}는 발송의 대칭으로 판매자 수동 배송 완료(SHIPPING→DELIVERED·E5)를
 * 처리한다. 소유권 해소(delivery→orderItem→seller)가 order 패키지 책임이므로 Admin 경로({@code DeliveryService.markDeliveredByAdmin})와
 * 달리 본 서비스에 위치한다(delivery는 order 무지 유지). 상태 전이·E5 발행은 primitive {@code markDelivered}를 재사용한다.
 */
@Service
@Transactional
public class OrderShippingService {

    private final OrderItemRepository orderItemRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryService deliveryService;

    public OrderShippingService(OrderItemRepository orderItemRepository, DeliveryRepository deliveryRepository,
            DeliveryService deliveryService) {
        this.orderItemRepository = orderItemRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryService = deliveryService;
    }

    /**
     * 판매자 수동 출고를 개시한다. 권한 검증 후 OrderItem PAID→PREPARING 전이·Delivery 생성(READY)·발송(SHIPPING)을
     * 단일 트랜잭션으로 일괄 처리한다. Order.status 최종 재계산은 markShipping이 발화한 E4를 소비하는
     * {@code DeliveryStartedHandler}가 SHIPPING으로 수행하므로 본 메서드는 별도 재계산을 하지 않는다.
     *
     * @param sellerId    요청 판매자 식별자(권한 대조)
     * @param orderItemId 출고 대상 OrderItem id
     * @param carrier     택배사
     * @param trackingNo  운송장번호(markShipping 필수)
     * @return 발송 완료된 Delivery(SHIPPING·claim_id NULL)
     * @throws OrderNotFoundException        OrderItem 미존재 또는 요청 판매자 소유가 아닌 경우(존재 은닉·404)
     * @throws DeliveryInvalidStateException OrderItem이 PAID가 아니어서 PREPARING 전이 불가한 경우(배송 개시 불가·422)
     */
    public Delivery prepareShipment(Long sellerId, Long orderItemId, DeliveryCarrier carrier, String trackingNo) {
        OrderItem orderItem = authorize(sellerId, orderItemId);

        // 가드 B: changeToPreparing은 markShipping(E4 발행) 前. recalc는 DeliveryStartedHandler가 최종 SHIPPING으로 수행(생략).
        changeToPreparing(orderItem);

        Delivery delivery = deliveryService.createForOrder(orderItemId, carrier);
        deliveryService.markShipping(delivery.getId(), trackingNo);
        return delivery;
    }

    /**
     * 요청 판매자의 OrderItem 접근 권한을 검증한다(권한 = Service 진입부·D-92). OrderItem 미존재와 소유자 불일치를 모두
     * {@link OrderNotFoundException}(404)으로 통일해 cross-tenant 존재 노출을 회피한다(OrderNotFoundException 계약 정합).
     *
     * @throws OrderNotFoundException OrderItem 미존재 또는 {@link OrderItem#getSellerId()} 불일치 시
     */
    private OrderItem authorize(Long sellerId, Long orderItemId) {
        OrderItem orderItem = orderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new OrderNotFoundException("주문 품목을 찾을 수 없습니다: orderItemId=" + orderItemId));
        if (!orderItem.getSellerId().equals(sellerId)) {
            throw new OrderNotFoundException("주문 품목을 찾을 수 없습니다: orderItemId=" + orderItemId);
        }
        return orderItem;
    }

    /**
     * OrderItem을 PREPARING으로 전이한다(PAID→PREPARING·판매자 출고 준비 시작·state-machine §3). 전이 합법성은
     * {@link OrderItem#changeStatus}가 검증한다(actor 비의존·D-92). Order.status 재계산은 후속 markShipping이 발화한 E4의
     * {@code DeliveryStartedHandler}가 SHIPPING으로 수행하므로 본 단계 재계산은 생략한다(중복 회피).
     *
     * <p>OrderItem이 PAID가 아니면(예: 이미 SHIPPING·중복 출고 시도) {@link OrderItem#changeStatus}가 {@link IllegalStateException}을
     * 던진다. 이를 {@link DeliveryInvalidStateException}(422)으로 흡수한다 — 직접 IllegalStateException 매핑은 500 fallback으로 새므로
     * 금지한다. 후속 {@code deliveryService.markShipping}은 방금 생성한 READY Delivery에 대해서만 호출되어(READY→SHIPPING 항상 합법·
     * trackingNo @NotBlank 보장) 도달 가능한 전이 위반이 없으므로 흡수 대상은 본 전이에 국한한다(handler 내부 예외 오분류 회피).
     */
    private void changeToPreparing(OrderItem orderItem) {
        try {
            orderItem.changeStatus(OrderItemStatus.PREPARING);
        } catch (IllegalStateException exception) {
            throw new DeliveryInvalidStateException("배송 개시할 수 없는 주문 품목 상태입니다: " + exception.getMessage());
        }
    }

    /**
     * 판매자 수동 배송 완료를 처리한다(Track 43·prepareShipment 대칭·M4). deliveryId로 배송을 조회해 소유권을 검증한 뒤
     * 배송 완료 전이(SHIPPING→DELIVERED·E5 발행)를 primitive에 위임한다. 전이 후 E5 동기 소비({@code DeliveryCompletedHandler})가
     * 같은 트랜잭션에서 OrderItem을 DELIVERED로 확정하고 Order.status를 재계산한다.
     *
     * <p><b>소유권(D-92·{@link #authorize} 계약 동일)</b>: Delivery는 sellerId를 직접 보유하지 않으므로
     * {@link Delivery#getOrderItemId()} → OrderItem → {@link OrderItem#getSellerId()} 1-hop으로 해소한다. 배송 미존재와 타 셀러
     * 소유를 모두 {@link DeliveryNotFoundException}(404)으로 통일해 cross-tenant 존재 노출을 회피한다.
     *
     * @param sellerId   요청 판매자 식별자(권한 대조)
     * @param deliveryId 배송 완료 대상 Delivery id
     * @throws DeliveryNotFoundException     배송 미존재 또는 요청 판매자 소유가 아닌 경우(존재 은닉·404)
     * @throws DeliveryInvalidStateException 배송이 SHIPPING이 아니어서 DELIVERED 전이 불가한 경우(배송 완료 불가·422)
     */
    public void markDeliveredBySeller(Long sellerId, Long deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new DeliveryNotFoundException("배송을 찾을 수 없습니다: deliveryId=" + deliveryId));
        authorizeDelivery(sellerId, delivery);
        try {
            deliveryService.markDelivered(deliveryId);
        } catch (IllegalStateException exception) {
            // 비-SHIPPING(READY·이미 DELIVERED) 배송의 DELIVERED 전이 위반(Delivery.markDelivered)을 422로 흡수한다 —
            // 직접 IllegalStateException 매핑은 500 fallback으로 새므로 금지(changeToPreparing 패턴 1:1·M4).
            throw new DeliveryInvalidStateException("배송 완료 처리할 수 없는 배송 상태입니다: " + exception.getMessage());
        }
    }

    /**
     * 요청 판매자가 해당 배송의 OrderItem 소유 셀러인지 검증한다(delivery→orderItem→seller 1-hop·Track 43). 배송이 참조하는
     * OrderItem 미존재와 소유자 불일치를 모두 {@link DeliveryNotFoundException}(404)으로 통일해 존재 노출을 회피한다.
     *
     * @throws DeliveryNotFoundException OrderItem 미존재 또는 {@link OrderItem#getSellerId()} 불일치 시
     */
    private void authorizeDelivery(Long sellerId, Delivery delivery) {
        OrderItem orderItem = orderItemRepository.findById(delivery.getOrderItemId())
                .orElseThrow(() -> new DeliveryNotFoundException("배송을 찾을 수 없습니다: deliveryId=" + delivery.getId()));
        if (!orderItem.getSellerId().equals(sellerId)) {
            throw new DeliveryNotFoundException("배송을 찾을 수 없습니다: deliveryId=" + delivery.getId());
        }
    }
}

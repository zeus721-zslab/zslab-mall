package com.zslab.mall.order.service;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.exception.OrderItemInvalidStateException;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer 구매확정 Application Service(Track 47·E6 수동 확정 경로). buyer가 배송완료(DELIVERED)된 OrderItem을
 * 구매확정(CONFIRMED)으로 전이하는 쓰기 경로다. Settlement 정산 트리거 상태(CONFIRMED)를 데이터로 확보하기 위한 선행이며,
 * PurchaseConfirmed 이벤트 발행·정산 적재는 본 트랙 범위 밖(Settlement 트랙 이월)이다.
 *
 * <p><b>소유권(D-92·getOrder 패턴 정합)</b>: 소유권 검증은 서비스 진입부에서 한다. Order를 orderPublicId로 조회해
 * buyerId를 대조하고, 대상 OrderItem이 그 주문 소속인지 items에서 orderItemPublicId로 매칭한다. 미존재·타인 주문·미소속 항목을
 * 모두 {@link OrderNotFoundException}(404)으로 통일해 존재 노출을 회피한다(§2).
 *
 * <p><b>멱등(DeliveryCompletedHandler 패턴)</b>: 대상이 이미 CONFIRMED이면 no-op으로 반환한다(재요청 안전). 그 외 상태는
 * {@link OrderItem#changeStatus}가 {@link OrderItemStatus#canTransitionTo}로 합법성을 검증한다.
 *
 * <p><b>422 흡수(OrderShippingService 패턴)</b>: DELIVERED가 아니어서 CONFIRMED 전이가 불가하면 {@link OrderItem#changeStatus}가
 * {@link IllegalStateException}을 던진다. 이를 {@link OrderItemInvalidStateException}(422)으로 흡수한다 —
 * 직접 IllegalStateException 매핑은 500 fallback으로 새므로 금지한다.
 */
@Slf4j
@Service
@Transactional
public class BuyerOrderConfirmService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public BuyerOrderConfirmService(OrderRepository orderRepository, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    /**
     * 구매확정을 처리한다. 소유권 검증 후 대상 OrderItem을 DELIVERED→CONFIRMED로 전이하고 Order.status를 재계산한다.
     *
     * @param buyerId            요청 buyer 식별자(소유권 대조)
     * @param orderPublicId      주문 public_id(ord_)
     * @param orderItemPublicId  확정 대상 주문 품목 public_id(oit_)
     * @return 확정된(또는 이미 확정 상태인) OrderItem
     * @throws OrderNotFoundException        주문 미존재·타 buyer 소유·항목 미소속인 경우(존재 은닉·404)
     * @throws OrderItemInvalidStateException OrderItem이 DELIVERED가 아니어서 CONFIRMED 전이 불가한 경우(구매확정 불가·422)
     */
    public OrderItem confirmPurchase(Long buyerId, String orderPublicId, String orderItemPublicId) {
        Order order = orderRepository.findByPublicIdWithItems(orderPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId);
        }

        OrderItem target = order.getItems().stream()
                .filter(item -> item.getPublicId().equals(orderItemPublicId))
                .findFirst()
                .orElseThrow(() -> new OrderNotFoundException(
                        "주문 품목을 찾을 수 없습니다: " + orderItemPublicId));

        if (target.getItemStatus() == OrderItemStatus.CONFIRMED) {
            // 멱등(이미 확정됨) — 재요청 안전 차단(DeliveryCompletedHandler 패턴)
            log.info("[Order] OrderItem 이미 CONFIRMED → 확정 건너뜀: orderItemPublicId={}", orderItemPublicId);
            return target;
        }

        try {
            target.changeStatus(OrderItemStatus.CONFIRMED);
        } catch (IllegalStateException exception) {
            throw new OrderItemInvalidStateException("구매확정할 수 없는 주문 품목 상태입니다: " + exception.getMessage());
        }
        orderService.recalculateStatus(order.getId());
        return target;
    }
}

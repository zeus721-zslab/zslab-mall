package com.zslab.mall.payment.handler;

import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.event.PaymentCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 결제 완료 이벤트 소비 핸들러(D-29·D-33). {@link PaymentCompleted}를 받아 주문 결제 완료를 반영한다.
 *
 * <p><b>동기·동일 트랜잭션(D-29)</b>: {@code @EventListener}로 동기 소비하며 발행자(PaymentService.handleCallback)와
 * 같은 트랜잭션에서 실행된다. 본 핸들러가 실패하면 결제 상태 전이까지 함께 롤백된다.
 * {@code @TransactionalEventListener(AFTER_COMMIT)}는 미사용이다.
 *
 * <p><b>Lazy 안전망(D-33)</b>: markPaid가 OrderItem을 순회하므로 {@link OrderRepository#findByIdWithItems}로
 * items를 선로딩한 뒤 markPaid를 호출한다. 동일 트랜잭션 영속성 컨텍스트라 markPaid 내부 재조회는 1차 캐시를 적중한다.
 */
@Component
public class OrderEventHandler {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public OrderEventHandler(OrderRepository orderRepository, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @EventListener
    public void onPaymentCompleted(PaymentCompleted event) {
        // D-33: items fetch join 선로딩(미존재 시 즉시 실패·롤백)
        orderRepository.findByIdWithItems(event.orderId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "PaymentCompleted 소비 실패·주문 미발견: orderId=" + event.orderId()));
        orderService.markPaid(event.orderId(), event.occurredAt());
    }
}

package com.zslab.mall.payment.handler;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.event.PaymentCompleted;
import com.zslab.mall.payment.exception.InvalidCallbackException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 결제 완료 이벤트 소비 핸들러(D-29·D-33). {@link PaymentCompleted}를 받아 주문 결제 완료를 반영한다.
 *
 * <p><b>동기·동일 트랜잭션(D-29)</b>: {@code @EventListener}로 동기 소비하며 발행자(PaymentService.handleCallback)와
 * 같은 트랜잭션에서 실행된다. 본 핸들러가 실패하면 결제 상태 전이까지 함께 롤백된다.
 * {@code @TransactionalEventListener(AFTER_COMMIT)}는 미사용이다.
 *
 * <p><b>늦은 승인 → 422(Track 78 D-167·보충으로 범위 축소)</b>: 미결제 종료(PAYMENT_EXPIRED) 주문에 뒤늦게 도착한 SUCCESS 콜백은
 * 선로딩한 Order.status가 PENDING_PAYMENT가 아닐 때 본 핸들러가 {@link InvalidCallbackException}을 던져 기존 REJECT 조합
 * (SUCCESS × 종결 상태)과 동일하게 422로 응답한다 — 500이면 실 PG가 재전송을 반복한다. {@code OrderService.markPaid}의 동일 status
 * 가드는 backstop으로 유지하며, 품목 불법 전이 등 그 외 {@link IllegalStateException}은 감싸지 않고 전파한다(데이터 이상은 500).
 * 주문 도메인은 결제 예외 체계를 참조하지 않는다(변환 책임은 결제 측 소비 핸들러).
 *
 * <p><b>실행 순서(D-173)</b>: {@code @org.springframework.core.annotation.Order(1)}(엔티티 Order와 이름 충돌로 FQCN)로 재고 차감 핸들러({@code InventoryPaymentCompletedHandler}·2)보다 먼저 실행해
 * 주문 전이 → 재고 차감 순서를 고정한다. Order 행 X 락은 발행자(PaymentService.lockOrderForApproval)가 이미 잡고 있으며
 * 본 핸들러의 status 가드는 backstop이다.
 *
 * <p><b>Lazy 안전망(D-33)</b>: markPaid가 OrderItem을 순회하므로 {@link OrderRepository#findByIdWithItems}로
 * items를 선로딩한 뒤 markPaid를 호출한다. 동일 트랜잭션 영속성 컨텍스트라 markPaid 내부 재조회는 1차 캐시를 적중한다.
 */
@Component
@org.springframework.core.annotation.Order(OrderEventHandler.HANDLER_ORDER)
public class OrderEventHandler {

    /** PaymentCompleted 동기 핸들러 실행 순서(D-173): 주문 전이(1) → 재고 차감(2). */
    public static final int HANDLER_ORDER = 1;

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public OrderEventHandler(OrderRepository orderRepository, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @EventListener
    public void onPaymentCompleted(PaymentCompleted event) {
        // D-33: items fetch join 선로딩(미존재 시 즉시 실패·롤백)
        Order order = orderRepository.findByIdWithItems(event.orderId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "PaymentCompleted 소비 실패·주문 미발견: orderId=" + event.orderId()));
        // 결제 전 단계 — Order.status가 원천 상태
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            // 늦은 웹훅(주문 이미 종료·결제 완료) → REJECT 422로 변환(트랜잭션은 그대로 롤백)
            throw new InvalidCallbackException(
                    "결제 승인 불가 상태의 주문입니다(늦은 콜백): orderId=" + event.orderId() + ", status=" + order.getStatus());
        }
        orderService.markPaid(event.orderId(), event.occurredAt());
    }
}

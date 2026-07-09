package com.zslab.mall.cart.handler;

import com.zslab.mall.cart.repository.CartItemRepository;
import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.payment.event.PaymentCompleted;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PaymentCompleted(E2) → CartItem 소비(장바구니 비우기) 핸들러(Track 67). {@link PaymentCompleted}를 소비해 orderId로 Order를
 * 재조회한 뒤(payload 무복제·D-30) 주문된 variant의 장바구니 품목을 buyer 스코프로 HARD DELETE한다.
 *
 * <p><b>소진 시점 = 결제 완료(Track 67)</b>: 종전 OrderPlaced(주문 생성) 소비에서 PaymentCompleted(결제 완료) 소비로 이동했다.
 * 결제 확정 전에는 카트를 비우지 않아, 주문은 생성됐으나 결제 실패/취소/중단된 경우 카트가 보존된다.
 *
 * <p><b>통합 정책(D-126·①)</b>: 주문 경로 무관(Cart Checkout·Buy Now 모두)하게 주문된 variant의 CartItem을 소비한다.
 * PaymentCompleted에 경로 정보가 없어 by-design 통합 정책으로, 직접주문(Buy Now)한 variant가 장바구니에 있으면 함께 제거된다.
 *
 * <p><b>실행 시점(D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로 결제 완료 커밋 후 별도
 * 트랜잭션에서 삭제한다(InventoryPaymentCompletedHandler·NotificationPaymentCompletedHandler 형제와 동형·원 흐름 비차단).
 *
 * <p><b>멱등·순서 독립(LT-05)</b>: HARD DELETE by (userId, variantId)는 재실행 시 대상이 이미 없어도 무해(0행)하며 형제 핸들러가
 * 세팅하는 상태 필드에 비의존한다. 상태 기반 skip 가드가 불필요해 PaymentCompleted AFTER_COMMIT 형제 순서 비결정(LT-05)을 자동 충족한다.
 *
 * <p><b>실패 격리</b>: 삭제 실패는 6 표준키 structured log 1줄 + zslab.event.failed 계측 후 흡수한다(결제 성패 무관·rethrow 금지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartPaymentCompletedHandler {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final EventMetricsRecorder eventMetricsRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(PaymentCompleted event) {
        try {
            Optional<Order> orderOptional = orderRepository.findByIdWithItems(event.orderId());
            if (orderOptional.isEmpty()) {
                // AFTER_COMMIT 후 주문 미발견은 비정상이나 삭제 대상 불명이므로 방어적으로 skip.
                log.warn("[Cart] event=PaymentCompleted target_id={} action=skip reason=order_not_found", event.orderId());
                return;
            }
            Order order = orderOptional.get();
            Set<Long> variantIds = order.getItems().stream()
                    .map(OrderItem::getVariantId)
                    .collect(Collectors.toSet());   // distinct 방어(동일 variant 복수 OrderItem 가능성 대비)
            long deleted = cartItemRepository.deleteByUserIdAndVariantIdIn(order.getBuyerId(), variantIds);
            log.info("[Cart] event=PaymentCompleted target_id={} action=consume buyer_id={} variant_count={} deleted={}",
                    event.orderId(), order.getBuyerId(), variantIds.size(), deleted);
        } catch (RuntimeException exception) {
            log.warn("[Cart] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "PaymentCompleted", "ORDER", event.orderId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
            eventMetricsRecorder.recordFailed(event.getClass().getSimpleName()); // zslab.event.failed{event} 계측
        }
    }
}

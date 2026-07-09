package com.zslab.mall.order.service;

import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.event.OrderCancelled;
import com.zslab.mall.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미결제 주문 자동취소 Application Service(D-153 Phase 1). 유예 경과한 PENDING_PAYMENT 주문 1건을 CANCELLED로 전이하고
 * {@link OrderCancelled}를 발행한다. 재고 예약 해제는 본 서비스에서 직접 호출하지 않고 {@code InventoryOrderCancelledHandler}
 * (OrderCancelled AFTER_COMMIT 소비)가 담당한다(관심사 분리·PaymentFailed 소비 패턴 동일).
 *
 * <p><b>단건 트랜잭션 경계</b>: {@link #cancelOne}은 주문 1건당 독립 {@code @Transactional}이다. 배치 오케스트레이션
 * ({@code OrderAutoCancelScheduler})은 트랜잭션 없이 id별로 본 메서드를 호출하므로 한 건 실패가 다른 건의 커밋을
 * 롤백하지 않는다(부분 실패 격리·ExpirePaymentService 경계 정합).
 *
 * <p><b>멱등</b>: 배치 조회~트랜잭션 사이 결제 완료·수동 취소로 status가 PENDING_PAYMENT가 아니게 되면 no-op skip한다.
 * Payment 도메인은 본 Phase에서 참조하지 않는다(Phase 2~4 정리 대상).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAutoCancelService {

    private final OrderRepository orderRepository;
    private final OrderStatusResolver orderStatusResolver;
    private final TracedEventPublisher eventPublisher;

    /**
     * PENDING_PAYMENT 주문 1건을 자동취소한다. 전 OrderItem을 CANCELLED로 전이하고 Order.status를 재파생(Resolver [5])한 뒤
     * {@link OrderCancelled}를 발행한다. status가 PENDING_PAYMENT가 아니면 멱등 no-op이다.
     *
     * @param orderId 자동취소 대상 주문 id
     */
    @Transactional
    public void cancelOne(Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId).orElse(null);
        if (order == null) {
            // 배치 조회~트랜잭션 사이 행이 사라지는 경우는 정상 흐름상 없으나 방어적으로 skip한다.
            log.info("[OrderAutoCancel] cancelOne skip: 주문 행 없음 orderId={}", orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            // 조회~트랜잭션 사이 결제 완료·수동 취소로 종결 전이됨(재검증 멱등).
            log.info("[OrderAutoCancel] cancelOne skip: PENDING_PAYMENT 아님 status={} orderId={}", order.getStatus(), orderId);
            return;
        }

        for (OrderItem item : order.getItems()) {
            // PENDING_PAYMENT 주문의 품목은 전부 ORDERED이나, ORDERED만 전이해 canTransitionTo 가드와 정합을 유지한다.
            if (item.getItemStatus() == OrderItemStatus.ORDERED) {
                item.changeStatus(OrderItemStatus.CANCELLED);
            }
        }

        List<OrderItemStatus> itemStatuses = order.getItems().stream()
                .map(OrderItem::getItemStatus)
                .toList();
        order.applyResolvedStatus(orderStatusResolver.resolve(itemStatuses));   // [5] 전 품목 CANCELLED → Order CANCELLED

        eventPublisher.publishEvent(new OrderCancelled(order.getPublicId(), order.getId(), LocalDateTime.now()));

        log.info("[OrderAutoCancel] cancelOne CANCELLED 전이 완료 orderId={} publicId={}", orderId, order.getPublicId());
    }
}

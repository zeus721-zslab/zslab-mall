package com.zslab.mall.order.service;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.exception.OrderItemInvalidStateException;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.exception.PurchaseConfirmBlockedException;
import com.zslab.mall.order.exception.PurchaseConfirmBlockedReason;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus;
import com.zslab.mall.reconciliation.repository.ReconciliationIssueRepository;
import com.zslab.mall.refund.repository.RefundRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer 구매확정 Application Service(Track 47·E6 수동 확정 경로). buyer가 배송완료(DELIVERED)된 OrderItem을
 * 구매확정(CONFIRMED)으로 전이하는 쓰기 경로다. 전이 성공 시 confirmed_at(정산 월 귀속 기준·Track 48 P3)을 기록한다.
 * PurchaseConfirmed 이벤트 발행·정산 적재는 본 트랙 범위 밖(정산 배치가 confirmed_at 기준으로 집계)이다 — 이벤트는 여전히 미발행.
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
    private final RefundRepository refundRepository;
    private final ReconciliationIssueRepository reconciliationIssueRepository;

    public BuyerOrderConfirmService(OrderRepository orderRepository, OrderService orderService, RefundRepository refundRepository,
            ReconciliationIssueRepository reconciliationIssueRepository) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.refundRepository = refundRepository;
        this.reconciliationIssueRepository = reconciliationIssueRepository;
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
     * @throws PurchaseConfirmBlockedException 품목 순수령액 0 이하·주문에 미해결 불일치(Track 104-4·422)
     */
    public OrderItem confirmPurchase(Long buyerId, String orderPublicId, String orderItemPublicId) {
        // Track 104-1 D-215(P5): 주문·품목을 적재하기 전에 주문 쓰기 락을 먼저 잡는다(형제 품목 변경과 직렬화·락 뒤 최신 품목으로 재계산).
        orderRepository.findIdByPublicId(orderPublicId).ifPresent(orderService::lockForWrite);
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

        confirmItem(target, order.getId());
        return target;
    }

    /**
     * 구매확정 코어(Track 81-B D-171·수동 {@link #confirmPurchase}·자동 {@code OrderAutoConfirmService} 공용). 소유권·멱등 판정은 호출부
     * 책임이며 본 메서드는 DELIVERED→CONFIRMED 전이·구매확정 가드·confirmed_at 기록·Order.status 재계산만 수행한다(정산 귀속 기준 동일).
     *
     * @throws OrderItemInvalidStateException  OrderItem이 DELIVERED가 아니어서 CONFIRMED 전이 불가한 경우(422)
     * @throws PurchaseConfirmBlockedException 품목 순수령액이 0 이하이거나 주문에 미해결 불일치가 있는 경우(422)
     */
    public void confirmItem(OrderItem target, Long orderId) {
        try {
            target.changeStatus(OrderItemStatus.CONFIRMED);
        } catch (IllegalStateException exception) {
            throw new OrderItemInvalidStateException("구매확정할 수 없는 주문 품목 상태입니다: " + exception.getMessage());
        }
        // 전이 합법성(상태 422)을 먼저 보고 돈·불일치 사실을 본다. 가드 예외는 트랜잭션을 롤백하므로 위 전이도 남지 않는다.
        requireConfirmable(target, orderId);
        // 전이 성공 직후 확정 시각 기록(정산 월 귀속 기준·Track 48 P3). markConfirmedAt은 기존값 미덮어쓰기 멱등 가드 보유.
        target.markConfirmedAt(LocalDateTime.now());
        orderService.recalculateStatus(orderId);
    }

    /**
     * 구매확정 가드(Track 104-4·P2·P6). 순수령액 = total_price − 기환불액(RefundedCondition·환불 개시 품목 상한과 같은 함수)이 0보다 커야 하고,
     * 주문에 미해결 불일치가 없어야 한다(정산 보류와 같은 의미). 호출부가 주문 쓰기 락을 쥔 뒤라, 같은 락을 먼저 잡는 환불·불일치 기록의
     * 최신 커밋을 읽는다(READ COMMITTED).
     */
    private void requireConfirmable(OrderItem target, Long orderId) {
        long refundedAmount = refundRepository.sumRefundedByOrderItemId(target.getId());
        long netAmount = target.getTotalPrice() - refundedAmount;
        if (netAmount <= 0) {
            log.warn("[Order] 구매확정 차단 — 순수령액 {}(품목 {} − 기환불 {}): orderItemId={}",
                    netAmount, target.getTotalPrice(), refundedAmount, target.getId());
            throw new PurchaseConfirmBlockedException(PurchaseConfirmBlockedReason.NET_AMOUNT_NOT_POSITIVE,
                    "환불로 결제 금액이 남지 않은 상품은 구매확정할 수 없습니다.");
        }
        if (reconciliationIssueRepository.existsByOrderIdAndStatus(orderId, ReconciliationIssueStatus.OPEN)) {
            log.warn("[Order] 구매확정 차단 — 미해결 불일치: orderId={} orderItemId={}", orderId, target.getId());
            throw new PurchaseConfirmBlockedException(PurchaseConfirmBlockedReason.RECONCILIATION_OPEN,
                    "주문에 확인 중인 결제·환불 문제가 있어 지금은 구매확정할 수 없습니다.");
        }
    }
}

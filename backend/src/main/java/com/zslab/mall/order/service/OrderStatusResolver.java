package com.zslab.mall.order.service;

import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Order.status 파생 Domain Service(state-machine.md §5·ORD-2).
 *
 * <p>한 Order의 OrderItem 상태 집합으로부터 Order.status를 산출한다. 외부 Aggregate를 참조하지 않는 순수 도메인 로직이다.
 *
 * <p><b>평가 순서</b>: 종료 상태 [5] → [6] → [7]을 먼저 본 뒤, CANCELLED를 뺀 품목을 진행 단계로 환산해 판정한다 — 모두 배송완료 이후면
 * DELIVERED · 하나라도 배송중 이후면 SHIPPING · 하나라도 준비중이면 PREPARING · 그 외 PAID. 셀러별 진행이 갈리는 혼합 주문(일부 구매확정·
 * 반품 요청·일부 취소)이 PAID로 퇴행하지 않게 하려는 것이다(최종 점검 K1).
 * 동기화 규칙 [1](결제 완료 시 PAID 일괄 적용)은 {@link OrderService#markPaid}가 직접 처리하며 본 Resolver를 경유하지 않는다.
 */
@Component
public class OrderStatusResolver {

    /** 확정 종결 집합(반품·교환 포함). [6]·[7] 판정에 사용한다. */
    private static final Set<OrderItemStatus> CONFIRMED_LIKE =
            EnumSet.of(OrderItemStatus.CONFIRMED, OrderItemStatus.RETURNED, OrderItemStatus.EXCHANGED);

    /** 배송완료 이후 단계. 반품·교환 요청(RETURN_REQUESTED·EXCHANGE_REQUESTED)은 이 단계로 간주한다(최종 점검 K1 확정 규칙 · 진입은 DELIVERED에서만 · D-266). */
    private static final Set<OrderItemStatus> DELIVERED_OR_LATER = EnumSet.of(
            OrderItemStatus.DELIVERED, OrderItemStatus.RETURN_REQUESTED, OrderItemStatus.EXCHANGE_REQUESTED,
            OrderItemStatus.CONFIRMED, OrderItemStatus.RETURNED, OrderItemStatus.EXCHANGED);

    /**
     * OrderItem 상태 집합으로부터 Order.status를 산출한다.
     *
     * @param itemStatuses 한 Order의 OrderItem 상태 목록(최소 1개·ORD-1)
     * @return 산출된 Order.status
     * @throws IllegalArgumentException 입력이 비었거나 null인 경우
     */
    public OrderStatus resolve(List<OrderItemStatus> itemStatuses) {
        if (itemStatuses == null || itemStatuses.isEmpty()) {
            throw new IllegalArgumentException("OrderItem 상태 집합은 비어 있을 수 없습니다(ORD-1).");
        }

        // [5] 모든 OrderItem = CANCELLED
        if (allMatch(itemStatuses, OrderItemStatus.CANCELLED)) {
            return OrderStatus.CANCELLED;
        }
        // [6] 일부 CANCELLED + 나머지 ∈ {CONFIRMED, RETURNED, EXCHANGED} (전체 CANCELLED는 [5]에서 처리됨)
        if (contains(itemStatuses, OrderItemStatus.CANCELLED)
                && allNonCancelledIn(itemStatuses, CONFIRMED_LIKE)) {
            return OrderStatus.PARTIAL_CANCEL;
        }
        // [7] 모든 OrderItem ∈ {CONFIRMED, RETURNED, EXCHANGED}
        if (allIn(itemStatuses, CONFIRMED_LIKE)) {
            return OrderStatus.CONFIRMED;
        }
        // 이후 CANCELLED 제외 품목의 진행 단계로 판정(CANCEL_REQUESTED·ORDERED·PAID는 결제 완료 단계)
        List<OrderItemStatus> activeStatuses = itemStatuses.stream()
                .filter(s -> s != OrderItemStatus.CANCELLED)
                .toList();
        // [4] 모두 배송완료 이후
        if (activeStatuses.stream().allMatch(DELIVERED_OR_LATER::contains)) {
            return OrderStatus.DELIVERED;
        }
        // [3] 하나라도 배송중 이후
        if (contains(activeStatuses, OrderItemStatus.SHIPPING)
                || activeStatuses.stream().anyMatch(DELIVERED_OR_LATER::contains)) {
            return OrderStatus.SHIPPING;
        }
        // [2] 하나라도 PREPARING
        if (contains(activeStatuses, OrderItemStatus.PREPARING)) {
            return OrderStatus.PREPARING;
        }
        // 기본: 결제 완료 상태
        return OrderStatus.PAID;
    }

    private boolean allMatch(List<OrderItemStatus> statuses, OrderItemStatus target) {
        return statuses.stream().allMatch(s -> s == target);
    }

    private boolean contains(List<OrderItemStatus> statuses, OrderItemStatus target) {
        return statuses.stream().anyMatch(s -> s == target);
    }

    private boolean allIn(List<OrderItemStatus> statuses, Set<OrderItemStatus> allowed) {
        return statuses.stream().allMatch(allowed::contains);
    }

    private boolean allNonCancelledIn(List<OrderItemStatus> statuses, Set<OrderItemStatus> allowed) {
        return statuses.stream()
                .filter(s -> s != OrderItemStatus.CANCELLED)
                .allMatch(allowed::contains);
    }
}

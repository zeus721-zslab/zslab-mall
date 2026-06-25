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
 * <p><b>평가 순서</b>: [5] → [6] → [7] → [4] → [3] → [2] (종료 상태 우선·진행 상태 역순). 어느 규칙도 맞지 않으면 PAID(기본).
 * 동기화 규칙 [1](결제 완료 시 PAID 일괄 적용)은 {@link OrderService#markPaid}가 직접 처리하며 본 Resolver를 경유하지 않는다.
 */
@Component
public class OrderStatusResolver {

    /** 확정 종결 집합(반품·교환 포함). [6]·[7] 판정에 사용한다. */
    private static final Set<OrderItemStatus> CONFIRMED_LIKE =
            EnumSet.of(OrderItemStatus.CONFIRMED, OrderItemStatus.RETURNED, OrderItemStatus.EXCHANGED);

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
        // [4] 모든 OrderItem = DELIVERED
        if (allMatch(itemStatuses, OrderItemStatus.DELIVERED)) {
            return OrderStatus.DELIVERED;
        }
        // [3] 최초(하나라도) SHIPPING
        if (contains(itemStatuses, OrderItemStatus.SHIPPING)) {
            return OrderStatus.SHIPPING;
        }
        // [2] 최초(하나라도) PREPARING
        if (contains(itemStatuses, OrderItemStatus.PREPARING)) {
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

package com.zslab.mall.order.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderItemStatus} 전이 매트릭스(QB-11) 검증. 12×12 전 조합을 오라클과 대조해 분기 100%를 커버한다.
 */
class OrderItemStatusTest {

    /** QB-11 합법 전이 오라클: 각 from의 유일한 합법 타깃(없으면 미등록). */
    private static Map<OrderItemStatus, OrderItemStatus> legalTarget() {
        Map<OrderItemStatus, OrderItemStatus> map = new EnumMap<>(OrderItemStatus.class);
        map.put(OrderItemStatus.ORDERED, OrderItemStatus.PAID);
        map.put(OrderItemStatus.PAID, OrderItemStatus.PREPARING);
        map.put(OrderItemStatus.PREPARING, OrderItemStatus.SHIPPING);
        map.put(OrderItemStatus.SHIPPING, OrderItemStatus.DELIVERED);
        map.put(OrderItemStatus.DELIVERED, OrderItemStatus.CONFIRMED);
        map.put(OrderItemStatus.CANCEL_REQUESTED, OrderItemStatus.CANCELLED);
        map.put(OrderItemStatus.RETURN_REQUESTED, OrderItemStatus.RETURNED);
        map.put(OrderItemStatus.EXCHANGE_REQUESTED, OrderItemStatus.EXCHANGED);
        return map;
    }

    @Test
    @DisplayName("canTransitionTo: 12×12 전 조합이 QB-11 오라클과 일치")
    void canTransitionTo_coversFullMatrix() {
        Map<OrderItemStatus, OrderItemStatus> oracle = legalTarget();
        for (OrderItemStatus from : OrderItemStatus.values()) {
            for (OrderItemStatus to : OrderItemStatus.values()) {
                boolean expected = oracle.get(from) == to && oracle.containsKey(from);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("종결 4값은 어떤 전이도 불가")
    void terminalStatuses_haveNoOutgoingTransition() {
        OrderItemStatus[] terminals = {
                OrderItemStatus.CONFIRMED,
                OrderItemStatus.CANCELLED,
                OrderItemStatus.RETURNED,
                OrderItemStatus.EXCHANGED
        };
        for (OrderItemStatus terminal : terminals) {
            for (OrderItemStatus to : OrderItemStatus.values()) {
                assertThat(terminal.canTransitionTo(to))
                        .as("종결 %s → %s 은 불가", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("역방향·건너뛰기 전이 차단 표본")
    void reverseAndSkipTransitions_blocked() {
        assertThat(OrderItemStatus.DELIVERED.canTransitionTo(OrderItemStatus.SHIPPING)).isFalse();
        assertThat(OrderItemStatus.SHIPPING.canTransitionTo(OrderItemStatus.PAID)).isFalse();
        assertThat(OrderItemStatus.ORDERED.canTransitionTo(OrderItemStatus.PREPARING)).isFalse();
        assertThat(OrderItemStatus.PAID.canTransitionTo(OrderItemStatus.DELIVERED)).isFalse();
    }

    @Test
    @DisplayName("DDL 정합: 12값 보유")
    void hasTwelveValues() {
        assertThat(OrderItemStatus.values()).hasSize(12);
    }
}

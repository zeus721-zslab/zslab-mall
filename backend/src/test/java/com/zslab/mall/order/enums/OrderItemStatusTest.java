package com.zslab.mall.order.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderItemStatus} 전이 매트릭스(QB-11·Track 9 PR-A 확장) 검증. 12×12 전 조합을 오라클과 대조해 분기 100%를 커버한다.
 */
class OrderItemStatusTest {

    /** QB-11 + Claim 진입 전이 오라클(Track 9 PR-A·D-88): 각 from의 합법 타깃 집합(종결·*_REQUESTED는 빈 집합). */
    private static Map<OrderItemStatus, Set<OrderItemStatus>> legalTargets() {
        Map<OrderItemStatus, Set<OrderItemStatus>> map = new EnumMap<>(OrderItemStatus.class);
        map.put(OrderItemStatus.ORDERED,           EnumSet.of(OrderItemStatus.PAID));
        map.put(OrderItemStatus.PAID,              EnumSet.of(OrderItemStatus.PREPARING,  OrderItemStatus.CANCEL_REQUESTED));
        map.put(OrderItemStatus.PREPARING,         EnumSet.of(OrderItemStatus.SHIPPING,   OrderItemStatus.CANCEL_REQUESTED));
        map.put(OrderItemStatus.SHIPPING,          EnumSet.of(OrderItemStatus.DELIVERED,  OrderItemStatus.RETURN_REQUESTED));
        map.put(OrderItemStatus.DELIVERED,         EnumSet.of(OrderItemStatus.CONFIRMED,  OrderItemStatus.RETURN_REQUESTED, OrderItemStatus.EXCHANGE_REQUESTED));
        map.put(OrderItemStatus.CONFIRMED,         EnumSet.noneOf(OrderItemStatus.class));
        map.put(OrderItemStatus.CANCEL_REQUESTED,  EnumSet.of(OrderItemStatus.CANCELLED));
        map.put(OrderItemStatus.CANCELLED,         EnumSet.noneOf(OrderItemStatus.class));
        map.put(OrderItemStatus.RETURN_REQUESTED,  EnumSet.of(OrderItemStatus.RETURNED));
        map.put(OrderItemStatus.RETURNED,          EnumSet.noneOf(OrderItemStatus.class));
        map.put(OrderItemStatus.EXCHANGE_REQUESTED, EnumSet.of(OrderItemStatus.EXCHANGED));
        map.put(OrderItemStatus.EXCHANGED,         EnumSet.noneOf(OrderItemStatus.class));
        return map;
    }

    @Test
    @DisplayName("canTransitionTo: 12×12 전 조합이 QB-11 오라클과 일치")
    void canTransitionTo_coversFullMatrix() {
        Map<OrderItemStatus, Set<OrderItemStatus>> oracle = legalTargets();
        for (OrderItemStatus from : OrderItemStatus.values()) {
            for (OrderItemStatus to : OrderItemStatus.values()) {
                boolean expected = oracle.get(from).contains(to);
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

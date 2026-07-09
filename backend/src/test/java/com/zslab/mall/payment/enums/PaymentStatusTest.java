package com.zslab.mall.payment.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PaymentStatus} 전이 매트릭스(state-machine.md §1·D-31·FE-12c) 검증. 5×5 전 조합을 오라클과 대조해 분기 100%를 커버한다.
 */
class PaymentStatusTest {

    /** 합법 전이 오라클: 각 from의 합법 타깃 집합(없으면 빈 집합). */
    private static Map<PaymentStatus, Set<PaymentStatus>> legalTargets() {
        Map<PaymentStatus, Set<PaymentStatus>> map = new EnumMap<>(PaymentStatus.class);
        map.put(PaymentStatus.PENDING, EnumSet.of(PaymentStatus.PAID, PaymentStatus.FAILED, PaymentStatus.EXPIRED));
        map.put(PaymentStatus.PAID, EnumSet.of(PaymentStatus.CANCELLED));
        map.put(PaymentStatus.FAILED, EnumSet.noneOf(PaymentStatus.class));
        map.put(PaymentStatus.CANCELLED, EnumSet.noneOf(PaymentStatus.class));
        map.put(PaymentStatus.EXPIRED, EnumSet.noneOf(PaymentStatus.class));
        return map;
    }

    @Test
    @DisplayName("canTransitionTo: 5×5 전 조합이 오라클과 일치")
    void canTransitionTo_coversFullMatrix() {
        Map<PaymentStatus, Set<PaymentStatus>> oracle = legalTargets();
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                boolean expected = oracle.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("PENDING → PAID·FAILED·EXPIRED 만 허용 (동일·CANCELLED 불가)")
    void pendingTransitions() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PAID)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.EXPIRED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PENDING)).isFalse();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("PAID → CANCELLED 만 허용 (PAID→PAID 멱등 차단)")
    void paidTransitions() {
        assertThat(PaymentStatus.PAID.canTransitionTo(PaymentStatus.CANCELLED)).isTrue();
        assertThat(PaymentStatus.PAID.canTransitionTo(PaymentStatus.PAID)).isFalse();
        assertThat(PaymentStatus.PAID.canTransitionTo(PaymentStatus.FAILED)).isFalse();
    }

    @Test
    @DisplayName("종결 상태(FAILED·CANCELLED·EXPIRED)는 어떤 전이도 불가")
    void terminalStatuses_haveNoOutgoingTransition() {
        for (PaymentStatus terminal : new PaymentStatus[] {
                PaymentStatus.FAILED, PaymentStatus.CANCELLED, PaymentStatus.EXPIRED}) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(terminal.canTransitionTo(to))
                        .as("종결 %s → %s 은 불가", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("DDL 정합: 5값 보유")
    void hasFiveValues() {
        assertThat(PaymentStatus.values()).hasSize(5);
    }
}

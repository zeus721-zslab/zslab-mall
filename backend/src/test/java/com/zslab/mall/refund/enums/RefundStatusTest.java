package com.zslab.mall.refund.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RefundStatus} 전이 매트릭스(state-machine.md §8·D-24·RFN-2) 검증. 3×3 전 조합을 오라클과 대조해 분기 100%를 커버한다.
 */
class RefundStatusTest {

    /** 합법 전이 오라클: 각 from의 합법 타깃 집합(종결 상태는 빈 집합). */
    private static Map<RefundStatus, Set<RefundStatus>> legalTargets() {
        Map<RefundStatus, Set<RefundStatus>> map = new EnumMap<>(RefundStatus.class);
        map.put(RefundStatus.PENDING, EnumSet.of(RefundStatus.COMPLETED, RefundStatus.FAILED));
        map.put(RefundStatus.COMPLETED, EnumSet.noneOf(RefundStatus.class));
        map.put(RefundStatus.FAILED, EnumSet.noneOf(RefundStatus.class));
        return map;
    }

    @Test
    @DisplayName("canTransitionTo: 3×3 전 조합이 오라클과 일치")
    void canTransitionTo_coversFullMatrix() {
        Map<RefundStatus, Set<RefundStatus>> oracle = legalTargets();
        for (RefundStatus from : RefundStatus.values()) {
            for (RefundStatus to : RefundStatus.values()) {
                boolean expected = oracle.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("PENDING → COMPLETED·FAILED 만 허용 (자기 전이 불가)")
    void pendingTransitions() {
        assertThat(RefundStatus.PENDING.canTransitionTo(RefundStatus.COMPLETED)).isTrue();
        assertThat(RefundStatus.PENDING.canTransitionTo(RefundStatus.FAILED)).isTrue();
        assertThat(RefundStatus.PENDING.canTransitionTo(RefundStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("종결 상태(COMPLETED·FAILED)는 어떤 전이도 불가(RFN-2 불가역)")
    void terminalStatuses_haveNoOutgoingTransition() {
        for (RefundStatus terminal : new RefundStatus[] {RefundStatus.COMPLETED, RefundStatus.FAILED}) {
            for (RefundStatus to : RefundStatus.values()) {
                assertThat(terminal.canTransitionTo(to))
                        .as("종결 %s → %s 은 불가", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("DDL 정합: 3값 보유")
    void hasThreeValues() {
        assertThat(RefundStatus.values()).hasSize(3);
    }
}

package com.zslab.mall.claim.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaimStatus} 전이 매트릭스(state-machine.md §2·CLM-4·CLM-1) 검증. 4×4 전 조합을 오라클과 대조해 분기 100%를 커버한다.
 */
class ClaimStatusTest {

    /** 합법 전이 오라클: 각 from의 합법 타깃 집합(종결 상태는 빈 집합). */
    private static Map<ClaimStatus, Set<ClaimStatus>> legalTargets() {
        Map<ClaimStatus, Set<ClaimStatus>> map = new EnumMap<>(ClaimStatus.class);
        map.put(ClaimStatus.REQUESTED, EnumSet.of(ClaimStatus.APPROVED, ClaimStatus.REJECTED));
        map.put(ClaimStatus.APPROVED, EnumSet.of(ClaimStatus.COMPLETED));
        map.put(ClaimStatus.REJECTED, EnumSet.noneOf(ClaimStatus.class));
        map.put(ClaimStatus.COMPLETED, EnumSet.noneOf(ClaimStatus.class));
        return map;
    }

    @Test
    @DisplayName("canTransitionTo: 4×4 전 조합이 오라클과 일치")
    void canTransitionTo_coversFullMatrix() {
        Map<ClaimStatus, Set<ClaimStatus>> oracle = legalTargets();
        for (ClaimStatus from : ClaimStatus.values()) {
            for (ClaimStatus to : ClaimStatus.values()) {
                boolean expected = oracle.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("REQUESTED → APPROVED·REJECTED 만 허용")
    void requestedTransitions() {
        assertThat(ClaimStatus.REQUESTED.canTransitionTo(ClaimStatus.APPROVED)).isTrue();
        assertThat(ClaimStatus.REQUESTED.canTransitionTo(ClaimStatus.REJECTED)).isTrue();
        assertThat(ClaimStatus.REQUESTED.canTransitionTo(ClaimStatus.COMPLETED)).isFalse();
        assertThat(ClaimStatus.REQUESTED.canTransitionTo(ClaimStatus.REQUESTED)).isFalse();
    }

    @Test
    @DisplayName("APPROVED → COMPLETED 만 허용 (본 트랙 실제 전이)")
    void approvedTransitions() {
        assertThat(ClaimStatus.APPROVED.canTransitionTo(ClaimStatus.COMPLETED)).isTrue();
        assertThat(ClaimStatus.APPROVED.canTransitionTo(ClaimStatus.REJECTED)).isFalse();
        assertThat(ClaimStatus.APPROVED.canTransitionTo(ClaimStatus.APPROVED)).isFalse();
    }

    @Test
    @DisplayName("종결 상태(REJECTED·COMPLETED)는 어떤 전이도 불가(CLM-1)")
    void terminalStatuses_haveNoOutgoingTransition() {
        for (ClaimStatus terminal : new ClaimStatus[] {ClaimStatus.REJECTED, ClaimStatus.COMPLETED}) {
            for (ClaimStatus to : ClaimStatus.values()) {
                assertThat(terminal.canTransitionTo(to))
                        .as("종결 %s → %s 은 불가", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("DDL 정합: 4값 보유")
    void hasFourValues() {
        assertThat(ClaimStatus.values()).hasSize(4);
    }
}

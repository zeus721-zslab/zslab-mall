package com.zslab.mall.seller.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SellerStatus#canTransitionTo} 전이표 단위 검증(Track 89-D·D-187). state-machine.md §7 매트릭스대로 허용 6전이만 true이고
 * 나머지 전 조합(같은 상태·TERMINATED에서의 전이·PENDING↔SUSPENDED·비-PENDING→PENDING)은 false다.
 */
class SellerStatusTest {

    @Test
    @DisplayName("허용 전이 6종: PENDING→ACTIVE·PENDING→TERMINATED·ACTIVE→SUSPENDED·SUSPENDED→ACTIVE·ACTIVE→TERMINATED·SUSPENDED→TERMINATED")
    void allowsSixTransitions() {
        assertThat(SellerStatus.PENDING.canTransitionTo(SellerStatus.ACTIVE)).isTrue();
        assertThat(SellerStatus.PENDING.canTransitionTo(SellerStatus.TERMINATED)).isTrue();
        assertThat(SellerStatus.ACTIVE.canTransitionTo(SellerStatus.SUSPENDED)).isTrue();
        assertThat(SellerStatus.SUSPENDED.canTransitionTo(SellerStatus.ACTIVE)).isTrue();
        assertThat(SellerStatus.ACTIVE.canTransitionTo(SellerStatus.TERMINATED)).isTrue();
        assertThat(SellerStatus.SUSPENDED.canTransitionTo(SellerStatus.TERMINATED)).isTrue();
    }

    @Test
    @DisplayName("허용 6전이 외 전 조합(16-6=10)은 차단: 같은 상태·TERMINATED→*·PENDING↔SUSPENDED·*→PENDING")
    void blocksEveryOtherCombination() {
        Set<String> allowed = Set.of("PENDING>ACTIVE", "PENDING>TERMINATED", "ACTIVE>SUSPENDED",
                "SUSPENDED>ACTIVE", "ACTIVE>TERMINATED", "SUSPENDED>TERMINATED");
        int blocked = 0;
        for (SellerStatus from : EnumSet.allOf(SellerStatus.class)) {
            for (SellerStatus to : EnumSet.allOf(SellerStatus.class)) {
                boolean expected = allowed.contains(from + ">" + to);
                assertThat(from.canTransitionTo(to)).as("%s → %s", from, to).isEqualTo(expected);
                if (!expected) {
                    blocked++;
                }
            }
        }
        assertThat(blocked).isEqualTo(10);
    }

    @Test
    @DisplayName("TERMINATED는 불가역: 4상태 전부로의 전이 false")
    void terminatedIsIrreversible() {
        for (SellerStatus to : EnumSet.allOf(SellerStatus.class)) {
            assertThat(SellerStatus.TERMINATED.canTransitionTo(to)).isFalse();
        }
    }
}

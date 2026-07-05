package com.zslab.mall.settlement.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SettlementStatus#canTransitionTo} 전이표 단위 검증(Track 49). state-machine.md §9 매트릭스대로 순방향
 * (PENDING→CONFIRMED→PAID)만 허용하고 역전·건너뛰기·불가역(PAID) 전이가 전부 차단됨을 확인한다.
 */
class SettlementStatusTest {

    @Test
    @DisplayName("순방향 전이만 허용: PENDING→CONFIRMED·CONFIRMED→PAID")
    void allowsForwardTransitions() {
        assertThat(SettlementStatus.PENDING.canTransitionTo(SettlementStatus.CONFIRMED)).isTrue();
        assertThat(SettlementStatus.CONFIRMED.canTransitionTo(SettlementStatus.PAID)).isTrue();
    }

    @Test
    @DisplayName("역전·건너뛰기 차단: PENDING→PAID·CONFIRMED→PENDING·PAID→CONFIRMED·PAID→PENDING")
    void blocksReverseAndSkipTransitions() {
        assertThat(SettlementStatus.PENDING.canTransitionTo(SettlementStatus.PAID)).isFalse();
        assertThat(SettlementStatus.CONFIRMED.canTransitionTo(SettlementStatus.PENDING)).isFalse();
        assertThat(SettlementStatus.PAID.canTransitionTo(SettlementStatus.CONFIRMED)).isFalse();
        assertThat(SettlementStatus.PAID.canTransitionTo(SettlementStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("자기 자신으로의 전이는 불허(멱등은 상위 no-op으로 처리)")
    void blocksSelfTransition() {
        assertThat(SettlementStatus.PENDING.canTransitionTo(SettlementStatus.PENDING)).isFalse();
        assertThat(SettlementStatus.CONFIRMED.canTransitionTo(SettlementStatus.CONFIRMED)).isFalse();
        assertThat(SettlementStatus.PAID.canTransitionTo(SettlementStatus.PAID)).isFalse();
    }
}

package com.zslab.mall.settlement.enums;

/**
 * 정산 상태(A#5·SETTLEMENT_STATUS·STL-2·3값). DDL {@code settlement.status} ENUM 정합.
 *
 * <p><b>전이 매트릭스(state-machine.md §9·STL-2)</b>: PENDING → CONFIRMED → PAID 순방향만 허용한다.
 * 역전(CONFIRMED→PENDING·PAID→*)은 전면 차단하며 PAID는 불가역 종결 상태다. 전이 강제는
 * {@link #canTransitionTo}로 하며(Settlement mutator가 이를 가드로 사용·OrderItemStatus 동일 패턴), 지급 트리거는
 * 운영자 수동 마킹만 존재한다(실 송금 연동·콜백 seam은 범위 밖).
 */
public enum SettlementStatus {
    PENDING,
    CONFIRMED,
    PAID;

    /**
     * 현재 상태에서 {@code next}로의 전이가 합법인지 판정한다(state-machine.md §9 매트릭스).
     *
     * @param next 목표 상태
     * @return 합법 전이면 true
     */
    public boolean canTransitionTo(SettlementStatus next) {
        return switch (this) {
            case PENDING -> next == CONFIRMED;
            case CONFIRMED -> next == PAID;
            // 종결 상태(불가역) — 어떤 전이도 불가
            case PAID -> false;
        };
    }
}

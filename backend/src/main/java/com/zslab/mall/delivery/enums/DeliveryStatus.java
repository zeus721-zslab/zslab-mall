package com.zslab.mall.delivery.enums;

/**
 * 배송 상태(A#12·DELIVERY_STATUS·3값). DDL {@code delivery.status} ENUM 정합.
 *
 * <p><b>전이 매트릭스(Track 13·D-97 Q1·state-machine.md §6.1)</b>: READY → SHIPPING → DELIVERED 단방향 직진.
 * DELIVERED는 종결 상태로 어떤 전이도 불가하며, 역방향·자기 전이·단계 건너뛰기를 전건 차단한다.
 * {@code OrderItemStatus.canTransitionTo} 패턴 1:1.
 */
public enum DeliveryStatus {
    READY,
    SHIPPING,
    DELIVERED;

    /**
     * 현재 상태에서 {@code next}로의 전이가 합법인지 판정한다(D-97 Q1 매트릭스).
     *
     * @param next 목표 상태
     * @return 합법 전이면 true
     */
    public boolean canTransitionTo(DeliveryStatus next) {
        return switch (this) {
            case READY -> next == SHIPPING;
            case SHIPPING -> next == DELIVERED;
            // 종결 상태는 어떤 전이도 불가
            case DELIVERED -> false;
        };
    }
}

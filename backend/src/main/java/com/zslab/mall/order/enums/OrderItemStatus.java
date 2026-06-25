package com.zslab.mall.order.enums;

/**
 * 주문 품목 상태(B분류·ORDER_ITEM_STATUS·12값). DDL {@code order_item.item_status} ENUM 정합.
 *
 * <p>OrderItem이 실제 상태를 보유하며(state-machine.md §3), Order.status는 본 값들의 집계 결과다(ORD-2).
 *
 * <p><b>전이 매트릭스(QB-11·state-machine.md §3 정합)</b>:
 * <ul>
 *   <li>진행 단계 순방향 인접 전이만 허용: ORDERED → PAID → PREPARING → SHIPPING → DELIVERED → CONFIRMED</li>
 *   <li>요청 상태(*_REQUESTED) → 대응 종결 상태로만: CANCEL_REQUESTED → CANCELLED 등</li>
 *   <li>종결 상태(CONFIRMED·CANCELLED·RETURNED·EXCHANGED) → 어떤 전이도 불가</li>
 *   <li>역방향·단계 건너뛰기 전이 차단(예: DELIVERED → SHIPPING)</li>
 * </ul>
 *
 * <p><b>Claim 진입 전이 이연</b>: 진행 단계 → *_REQUESTED 전이(취소·반품·교환 요청 진입)는 Claim 도메인 소관이며
 * Track 5(Refund Flow)에서 Claim 이벤트 소비 로직과 함께 본 매트릭스에 추가한다. 본 트랙은 QB-11 4개 규칙만 확정한다.
 */
public enum OrderItemStatus {
    ORDERED,
    PAID,
    PREPARING,
    SHIPPING,
    DELIVERED,
    CONFIRMED,
    CANCEL_REQUESTED,
    CANCELLED,
    RETURN_REQUESTED,
    RETURNED,
    EXCHANGE_REQUESTED,
    EXCHANGED;

    /**
     * 현재 상태에서 {@code next}로의 전이가 합법인지 판정한다(QB-11 매트릭스).
     *
     * @param next 목표 상태
     * @return 합법 전이면 true
     */
    public boolean canTransitionTo(OrderItemStatus next) {
        return switch (this) {
            case ORDERED -> next == PAID;
            case PAID -> next == PREPARING;
            case PREPARING -> next == SHIPPING;
            case SHIPPING -> next == DELIVERED;
            case DELIVERED -> next == CONFIRMED;
            case CANCEL_REQUESTED -> next == CANCELLED;
            case RETURN_REQUESTED -> next == RETURNED;
            case EXCHANGE_REQUESTED -> next == EXCHANGED;
            // 종결 상태는 어떤 전이도 불가
            case CONFIRMED, CANCELLED, RETURNED, EXCHANGED -> false;
        };
    }
}

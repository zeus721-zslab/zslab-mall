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
 * <p><b>Claim 진입 전이 매트릭스(Track 9 PR-A·D-88 Q1~Q4·5건)</b>: PAID·PREPARING → CANCEL_REQUESTED(배송 전 취소)·
 * SHIPPING·DELIVERED → RETURN_REQUESTED(출고 후 반품)·DELIVERED → EXCHANGE_REQUESTED(수령 후 교환).
 * 정책 차단(D-88 Q1·Q2·Q3): SHIPPING→CANCEL_REQUESTED·SHIPPING→EXCHANGE_REQUESTED·CONFIRMED→*_REQUESTED 전건 차단.
 * 시그니처(D-88 Q4): {@code canTransitionTo(next)} 단일 인자 유지(ClaimType 무관·책임 분리).
 *
 * <p><b>Claim 복귀 전이(Track 14·D-98 Q7 스냅샷 기반·D-90 Q3 의미 변경)</b>: ClaimRejected 핸들러가
 * {@code claim.previous_order_item_status}(D-98 Q11) 스냅샷을 기반으로 요청 시점 상태로 원복한다.
 * 스냅샷 기반이므로 CANCEL_REQUESTED → PAID·PREPARING, RETURN_REQUESTED → SHIPPING·DELIVERED,
 * EXCHANGE_REQUESTED → DELIVERED를 허용한다. D-90 Q3의 CANCEL_REQUESTED → PAID 고정 환원(claim-lock release)은
 * 본 결정으로 의미 변경되었으며 claim-lock release 단어는 더 이상 의미 부재다.
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
            // ORDERED → CANCELLED: 결제 전 미결제 자동취소 경로(D-153 Phase 1)
            case ORDERED -> next == PAID || next == CANCELLED;
            case PAID -> next == PREPARING || next == CANCEL_REQUESTED;
            case PREPARING -> next == SHIPPING || next == CANCEL_REQUESTED;
            case SHIPPING -> next == DELIVERED || next == RETURN_REQUESTED;
            case DELIVERED -> next == CONFIRMED || next == RETURN_REQUESTED || next == EXCHANGE_REQUESTED;
            case CANCEL_REQUESTED -> next == CANCELLED || next == PAID || next == PREPARING;
            case RETURN_REQUESTED -> next == RETURNED || next == SHIPPING || next == DELIVERED;
            case EXCHANGE_REQUESTED -> next == EXCHANGED || next == DELIVERED;
            // 종결 상태는 어떤 전이도 불가
            case CONFIRMED, CANCELLED, RETURNED, EXCHANGED -> false;
        };
    }
}

package com.zslab.mall.refund.enums;

/**
 * 환불 상태(A분류·A#15·3값·RFN-2). DDL {@code refund.status} ENUM 정합.
 *
 * <p><b>전이 매트릭스(state-machine.md §8·D-24 1:1)</b>:
 * <ul>
 *   <li>PENDING → COMPLETED: PG 환불 콜백 성공(불가역)</li>
 *   <li>PENDING → FAILED: PG 환불 콜백 실패·PG 호출 예외 포함(불가역·D-67·CR-03)</li>
 *   <li>종결 상태(COMPLETED·FAILED) → 어떤 전이도 불가(RFN-2). 재시도는 새 Refund 행</li>
 * </ul>
 */
public enum RefundStatus {
    PENDING,
    COMPLETED,
    FAILED;

    /**
     * 현재 상태에서 {@code next}로의 전이가 합법인지 판정한다(state-machine.md §8·D-24).
     *
     * @param next 목표 상태
     * @return 합법 전이면 true. 동일 상태 전이·종결 상태(COMPLETED·FAILED)에서의 전이는 false(RFN-2)
     */
    public boolean canTransitionTo(RefundStatus next) {
        return switch (this) {
            case PENDING -> next == COMPLETED || next == FAILED;
            // 종결 상태는 어떤 전이도 불가(RFN-2·재시도는 새 행)
            case COMPLETED, FAILED -> false;
        };
    }
}

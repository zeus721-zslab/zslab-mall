package com.zslab.mall.payment.enums;

/**
 * 결제 상태(A분류·A#10·5값·PAY-2). DDL {@code payment.status} ENUM 정합.
 *
 * <p><b>전이 매트릭스(state-machine.md §1·D-31·FE-12c 정합)</b>:
 * <ul>
 *   <li>PENDING → PAID: PG 결제 성공 콜백(SUCCESS × PENDING)</li>
 *   <li>PENDING → FAILED: PG 실패 콜백(FAILURE × PENDING·재시도 가능한 PG 실패·D-34)</li>
 *   <li>PENDING → EXPIRED: 미결제 종료(결제창 이탈·30분 만료·시작 실패·FE-12c). 결제 생명주기 종료(재시도는 새 행)</li>
 *   <li>PAID → CANCELLED: 결제 취소 콜백(CANCEL × PAID·Track 5 환불 흐름 진입)</li>
 *   <li>종결 상태(FAILED·CANCELLED·EXPIRED) → 어떤 전이도 불가. 재시도는 새 Payment 행 생성(D-28)</li>
 * </ul>
 *
 * <p><b>OrderStatus와의 차이(D-36)</b>: 본 enum은 OrderItemStatus처럼 단일 행의 직접 전이 대상이므로
 * {@code canTransitionTo}로 합법성을 강제한다. Order.status(집계 캐시·Resolver 파생)와 달리 별도 Domain Service를 두지 않으며,
 * 본 enum이 도메인 전이 규칙을 단일 책임으로 담당한다.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    CANCELLED,
    /** 미결제 종료(결제창 이탈·30분 만료·시작 실패). FAILED(재시도 가능 PG 실패)와 구분하는 결제 생명주기 종료(FE-12c). */
    EXPIRED;

    /**
     * 현재 상태에서 {@code next}로의 전이가 합법인지 판정한다(state-machine.md §1·D-31·FE-12c).
     *
     * @param next 목표 상태
     * @return 합법 전이면 true. 동일 상태로의 전이(PAID→PAID 등)·종결 상태에서의 전이는 false
     */
    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING -> next == PAID || next == FAILED || next == EXPIRED;
            case PAID -> next == CANCELLED;
            // 종결 상태는 어떤 전이도 불가(재시도는 새 행)
            case FAILED, CANCELLED, EXPIRED -> false;
        };
    }
}

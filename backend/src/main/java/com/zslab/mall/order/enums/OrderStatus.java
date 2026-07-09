package com.zslab.mall.order.enums;

/**
 * 주문 상태(B분류·ORDER_STATUS·9값). DDL {@code order.status} ENUM 정합.
 *
 * <p>Order.status는 OrderItem 집계 캐시이며(state-machine.md §4·ORD-2),
 * {@link com.zslab.mall.order.service.OrderStatusResolver}가 OrderItem 상태 집합으로부터 파생·갱신한다.
 * 단 {@link #PENDING_PAYMENT}·{@link #PAYMENT_EXPIRED}는 Resolver 파생 대상이 아니라 주문 생성·미결제 종료 시
 * 직접 세팅되는 값이다(item 집계로 산출되지 않음·FE-12c).
 *
 * <p><b>canTransitionTo 부재 사유</b>: 본 enum은 의도적으로 전이 검증 메서드를 두지 않는다.
 * Order.status는 직접 전이 대상이 아니라 Resolver 파생 결과이므로(ORD-2), 전이 매트릭스를 두면
 * Resolver 출력을 거부하는 별도 실패 경로가 생겨 설계 의도와 충돌한다. 상태 합법성은 Resolver가 단일 책임으로 보장한다.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PREPARING,
    SHIPPING,
    DELIVERED,
    CONFIRMED,
    CANCELLED,
    PARTIAL_CANCEL,
    /** 미결제 종료(결제창 이탈·PG오류·30분 만료·시작 실패). 구매자 목록 비노출·삭제 대상(FE-12c·D-153 레벨3). */
    PAYMENT_EXPIRED
}

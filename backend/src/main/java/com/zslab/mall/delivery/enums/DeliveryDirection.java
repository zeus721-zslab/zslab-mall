package com.zslab.mall.delivery.enums;

/**
 * 배송 방향(Track 81-A D-170). DDL {@code delivery.direction} ENUM 정합.
 *
 * <p>OUTBOUND는 구매자에게 보내는 발송(일반 주문·교환품·검수 불합격 재발송), RETURN은 구매자가 보내는 반품 회수다.
 * 발송 이벤트 소비처(품목 SHIPPING/DELIVERED 전이·배송 알림·교환 차액 환불)는 OUTBOUND만 처리한다 — 회수 Delivery가 품목 상태를
 * 건드리면 RETURN_REQUESTED → SHIPPING(스냅샷 원복용 합법 전이)으로 오염될 수 있다.
 */
public enum DeliveryDirection {
    OUTBOUND,
    RETURN
}

package com.zslab.mall.order.event;

import java.time.LocalDateTime;

/**
 * 주문 취소 도메인 이벤트(D-153 Phase 1). Spring ApplicationEvent로 발행한다({@link OrderPlaced} 형태 미러).
 *
 * <p>payload는 식별자·시각 3필드로 한정한다(QB-13 정합). 소비측(Inventory 예약 해제 InventoryOrderCancelledHandler)은
 * {@code orderId}로 OrderItem을 재조회해 처리한다(도메인 상태 복제 방지·PaymentFailed 소비 패턴 동일).
 */
public record OrderCancelled(
        String publicId,
        Long orderId,
        LocalDateTime occurredAt) {
}

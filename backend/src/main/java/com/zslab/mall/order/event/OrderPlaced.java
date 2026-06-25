package com.zslab.mall.order.event;

import java.time.LocalDateTime;

/**
 * 주문 생성 도메인 이벤트(E1·QB-13). Spring ApplicationEvent로 발행한다.
 *
 * <p>payload는 식별자·시각 3필드로 한정한다(QB-13). Order 엔티티 통째·OrderItem 목록·금액 전달 금지 —
 * 소비측(Inventory 예약·CartItem 소비·NotificationLog)은 publicId/orderId로 재조회한다. 핸들러는 Track 7 이연.
 */
public record OrderPlaced(
        String publicId,
        Long orderId,
        LocalDateTime occurredAt) {
}

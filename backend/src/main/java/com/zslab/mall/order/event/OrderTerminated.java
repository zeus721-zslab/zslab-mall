package com.zslab.mall.order.event;

import java.time.LocalDateTime;

/**
 * 주문 종료 도메인 이벤트(FE-12c·D-153 레벨3·구 OrderCancelled 개명). Spring ApplicationEvent로 발행한다({@link OrderPlaced} 형태 미러).
 *
 * <p>주문 생명주기가 종료(미결제 종료 PAYMENT_EXPIRED·향후 취소 등)될 때 발행하며, 재고 예약 해제는 본 이벤트 단일 구독으로
 * 수렴한다(원칙 3: Inventory는 주문 종료만 구독). payload는 식별자·시각 3필드로 한정하고(QB-13 정합), 소비측
 * ({@code InventoryOrderTerminatedHandler})은 {@code orderId}로 OrderItem을 재조회해 처리한다(도메인 상태 복제 방지).
 */
public record OrderTerminated(
        String publicId,
        Long orderId,
        LocalDateTime occurredAt) {
}

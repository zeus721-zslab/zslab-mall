package com.zslab.mall.payment.event;

import java.time.LocalDateTime;

/**
 * 결제 실패 도메인 이벤트(E3·D-30). Spring ApplicationEvent로 동기 발행한다(D-29).
 *
 * <p>payload는 사실 통지 원칙에 따라 식별자·실패코드·시각으로 한정한다(D-30). items[] 제거 — Inventory 예약 해제 핸들러는
 * {@code orderId}로 OrderItem을 직접 조회 후 처리한다(도메인 상태 복제 방지).
 */
public record PaymentFailed(
        Long paymentId,
        Long orderId,
        String failureCode,
        LocalDateTime occurredAt) {
}

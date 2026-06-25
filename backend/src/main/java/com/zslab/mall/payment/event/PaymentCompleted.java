package com.zslab.mall.payment.event;

import java.time.LocalDateTime;

/**
 * 결제 완료 도메인 이벤트(E2·D-30). Spring ApplicationEvent로 동기 발행한다(D-29).
 *
 * <p>payload는 사실 통지 원칙에 따라 식별자·금액·거래ID·시각으로 한정한다(D-30). items[] 등 도메인 상태 복제는 금지하며,
 * 소비측(OrderEventHandler·Inventory 등)은 {@code orderId}로 필요한 데이터를 재조회한다.
 *
 * <p>멱등 키는 {@code pgTransactionId}다. 동일 PG 거래의 중복 통지는 소비측에서 무시한다.
 */
public record PaymentCompleted(
        Long paymentId,
        Long orderId,
        Long amount,
        String pgTransactionId,
        LocalDateTime occurredAt) {
}

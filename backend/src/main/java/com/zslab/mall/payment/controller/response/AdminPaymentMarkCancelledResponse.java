package com.zslab.mall.payment.controller.response;

import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentStatus;

/**
 * Admin 결제 취소 응답 DTO(Track 28 D-113). 취소 처리 후 결제 상태(status)를 노출한다.
 *
 * <p>내부 PK(id·orderId)는 노출하지 않는다(Controller 단일 책임·내부 BIGINT 노출 회피·D-40 §2·InventoryAdjustResponse 정합).
 * paymentPublicId는 요청 경로 식별자를 그대로 반향한다.
 */
public record AdminPaymentMarkCancelledResponse(
        String paymentPublicId,
        PaymentStatus status) {

    public static AdminPaymentMarkCancelledResponse from(String paymentPublicId, Payment payment) {
        return new AdminPaymentMarkCancelledResponse(paymentPublicId, payment.getStatus());
    }
}

package com.zslab.mall.payment.exception;

/**
 * 결제 행을 찾을 수 없을 때 발생한다(Track 28 D-113·404). Admin 수동 취소 시 paymentPublicId 미조회·
 * {@code markCancelled} paymentId 미조회 시 던진다. GlobalExceptionHandler가 404로 매핑한다.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String message) {
        super(message);
    }
}

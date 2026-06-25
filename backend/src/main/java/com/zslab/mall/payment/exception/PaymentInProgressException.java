package com.zslab.mall.payment.exception;

/**
 * 유효한(미만료) PENDING 결제 행이 존재해 새 결제 시도를 차단할 때 발생한다(D-32).
 *
 * <p>발생 지점: {@code PaymentService.initiate} — 기존 PENDING 행이 있고 {@code now < expires_at}인 경우.
 * 만료된 PENDING은 차단 대상이 아니며 새 시도를 허용한다(D-32).
 */
public class PaymentInProgressException extends RuntimeException {

    public PaymentInProgressException(String message) {
        super(message);
    }
}

package com.zslab.mall.payment.exception;

/**
 * 한 주문에 이미 PAID 결제 행이 존재할 때 발생한다(PAY-3a·D-31). 중복 결제·과결제 방어선이다.
 *
 * <p>발생 지점: {@code PaymentService.initiate} 진입 가드, handleCallback의 PAID 전이 직전 재검증.
 */
public class PaymentAlreadyCompletedException extends RuntimeException {

    public PaymentAlreadyCompletedException(String message) {
        super(message);
    }
}

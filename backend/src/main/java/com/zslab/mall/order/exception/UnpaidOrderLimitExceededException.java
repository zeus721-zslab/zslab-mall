package com.zslab.mall.order.exception;

/**
 * 구매자의 미결제(PENDING_PAYMENT) 주문이 동시 보유 한도에 도달해 신규 주문을 거부할 때 발생한다(D-268 SEC-02).
 * 전역 예외 핸들러가 HTTP 422 {@code UNPAID_ORDER_LIMIT_EXCEEDED}로 응답한다.
 */
public class UnpaidOrderLimitExceededException extends RuntimeException {

    public UnpaidOrderLimitExceededException(String message) {
        super(message);
    }
}

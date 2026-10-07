package com.zslab.mall.order.exception;

/**
 * 구매자의 미결제(PENDING_PAYMENT) 주문에 예약된 같은 variant 수량과 이번 주문 수량의 합이 상한을 넘어 신규 주문을 거부할 때 발생한다
 * (SEC-02 잔여). 전역 예외 핸들러가 HTTP 422 {@code UNPAID_VARIANT_QUANTITY_LIMIT_EXCEEDED}로 응답한다.
 */
public class UnpaidVariantQuantityLimitExceededException extends RuntimeException {

    public UnpaidVariantQuantityLimitExceededException(String message) {
        super(message);
    }
}

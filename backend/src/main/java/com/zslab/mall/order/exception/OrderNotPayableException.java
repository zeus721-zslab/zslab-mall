package com.zslab.mall.order.exception;

/**
 * 재결제 재검증(D-51·D-60) 2종 차단 시 발생한다. 전역 예외 핸들러가 HTTP 422 {@code ORDER_NOT_PAYABLE}로 응답하며,
 * {@link #getReason()}를 ProblemDetail.detail 코드(PRODUCT_NOT_ON_SALE·OUT_OF_STOCK)로 노출한다(§6).
 */
public class OrderNotPayableException extends RuntimeException {

    private final transient OrderNotPayableReason reason;

    public OrderNotPayableException(OrderNotPayableReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public OrderNotPayableReason getReason() {
        return reason;
    }
}

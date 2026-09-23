package com.zslab.mall.order.exception;

/**
 * 구매확정 가드에 걸렸을 때 발생한다(Track 104-4). 전역 예외 핸들러가 HTTP 422로 응답하며 {@link #getReason()}별로 code를 나눈다.
 * 메시지는 구매자 화면에 그대로 표시되므로 내부 식별자를 넣지 않는다.
 */
public class PurchaseConfirmBlockedException extends RuntimeException {

    private final transient PurchaseConfirmBlockedReason reason;

    public PurchaseConfirmBlockedException(PurchaseConfirmBlockedReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PurchaseConfirmBlockedReason getReason() {
        return reason;
    }
}

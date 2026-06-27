package com.zslab.mall.refund.exception;

/**
 * 환불 행을 찾을 수 없을 때 발생한다. webhook 콜백 pg_refund_id 미매칭·markFailed refundId 미매칭 시 던진다.
 */
public class RefundNotFoundException extends RuntimeException {

    public RefundNotFoundException(String message) {
        super(message);
    }
}

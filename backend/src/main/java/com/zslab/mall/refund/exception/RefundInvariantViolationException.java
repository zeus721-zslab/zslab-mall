package com.zslab.mall.refund.exception;

/**
 * 환불 불변조건(PAY-1·RFN-1) 위반 시 발생한다.
 *
 * <p>대표 케이스:
 * <ul>
 *   <li>PAY-1: Σ(Refund.COMPLETED.amount) + 신규 amount &gt; Payment.amount (과환불 차단·initiate 사전·markCompleted 사후)</li>
 *   <li>RFN-1: pg_refund_id 없이 COMPLETED 전이 시도</li>
 * </ul>
 */
public class RefundInvariantViolationException extends RuntimeException {

    public RefundInvariantViolationException(String message) {
        super(message);
    }
}

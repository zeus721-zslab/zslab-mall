package com.zslab.mall.refund.exception;

/**
 * 동일 pg_refund_id로 이미 COMPLETED된 환불에 markCompleted가 재호출됐음을 알리는 멱등 no-op 시그널이다(RFN-3).
 *
 * <p>webhook 처리 경로({@code RefundService.handleCallback})는 markCompleted 호출 전에 COMPLETED 여부를 선검사해
 * 본 예외 없이 200 no-op으로 응답한다. 본 예외는 markCompleted를 직접 호출하는 경로(단위 테스트·방어)에서 RFN-3 위반을
 * 식별하기 위한 도메인 시그널이며, 오류가 아니라 "이미 처리됨" 신호다.
 */
public class RefundIdempotentNoOpException extends RuntimeException {

    public RefundIdempotentNoOpException(String message) {
        super(message);
    }
}

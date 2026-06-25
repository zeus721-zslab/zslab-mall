package com.zslab.mall.payment.exception;

/**
 * 콜백 타입 × 현재 상태 조합이 비정상(REJECT)이라 처리할 수 없을 때 발생한다(D-34 매트릭스).
 *
 * <p>REJECT 케이스: SUCCESS × FAILED·SUCCESS × CANCELLED 등 종결 상태로의 불법 전이 요청. Controller는 본 예외를 HTTP 422로 응답한다(D-34).
 * 결제 행을 찾지 못한 경우(attempt_key 미매칭)도 본 예외로 통일한다.
 */
public class InvalidCallbackException extends RuntimeException {

    public InvalidCallbackException(String message) {
        super(message);
    }
}

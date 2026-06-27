package com.zslab.mall.checkout.exception;

/**
 * 동일 Idempotency-Key 요청이 아직 진행 중(IN_PROGRESS·order_id 미할당)일 때 발생한다(D-52·§8).
 * 전역 예외 핸들러가 HTTP 409 {@code IDEMPOTENCY_KEY_IN_PROGRESS}로 응답한다.
 */
public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException(String message) {
        super(message);
    }
}

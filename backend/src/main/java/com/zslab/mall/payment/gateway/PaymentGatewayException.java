package com.zslab.mall.payment.gateway;

/**
 * PG 결제 시도 등록(redirect 발급) 실패를 나타낸다(§5·§7). {@code CheckoutService}가 이를 잡아 결제 시작 실패
 * 응답(INITIATE_FAILED)으로 변환하고 §18 운영 로그 5필드를 남긴다. {@code PaymentService.initiate}의 트랜잭션은
 * 이 예외로 롤백되어 Payment row가 저장되지 않는다(§5 부분 실패 정책).
 *
 * <p>{@link MockPaymentGateway}는 본 예외를 던지지 않는다(항상 성공). 실 PG 구현·테스트 mock이 PG 장애 시 던진다.
 */
public class PaymentGatewayException extends RuntimeException {

    private final String attemptKey;
    private final String failureCode;

    public PaymentGatewayException(String attemptKey, String failureCode, String message) {
        super(message);
        this.attemptKey = attemptKey;
        this.failureCode = failureCode;
    }

    public String getAttemptKey() {
        return attemptKey;
    }

    public String getFailureCode() {
        return failureCode;
    }
}

package com.zslab.mall.payment.exception;

/**
 * 결제 상태 전이가 현재 상태에서 불가할 때 발생한다(D-172 보충·422). 환불 완료 콜백 동기 체인의 {@code PaymentService.markCancelled}가
 * PAID가 아닌 결제를 CANCELLED로 보내려 할 때 엔티티 {@code IllegalStateException}을 이 예외로 흡수한다 — 직접 IllegalStateException 매핑은
 * 500 fallback으로 새므로 금지(OrderShippingService 선례). 콜백 TX는 롤백되고 PG가 재전송한다.
 */
public class PaymentInvalidStateException extends RuntimeException {
    public PaymentInvalidStateException(String message) {
        super(message);
    }
}

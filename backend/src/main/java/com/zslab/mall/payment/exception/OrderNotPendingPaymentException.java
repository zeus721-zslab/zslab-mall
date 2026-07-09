package com.zslab.mall.payment.exception;

/**
 * 결제 시작(initiate) 대상 주문이 PENDING_PAYMENT가 아닐 때 발생한다(FE-12c-2·미결제 종료 후 결제 재시작 차단).
 *
 * <p>미결제 종료(PAYMENT_EXPIRED)·결제 완료(PAID) 등 이미 종료·진행된 주문에 결제 시작 콜이 도달하면 차단해,
 * 삭제 대상(PAYMENT_EXPIRED) 주문에 새 PENDING payment 자식 행이 생기는 동시성 창을 닫는다(삭제 배치 안전성 근본 가드).
 * INITIATE_FAILED로 PENDING_PAYMENT가 유지된 주문의 재결제는 통과한다.
 *
 * <p>발생 지점: {@code PaymentService.initiate} 본인검증 직후 status 가드. 전역 예외 핸들러가 HTTP 422로 응답한다
 * (요청은 well-formed이나 서버 상태로 처리 불가·{@link PaymentAlreadyCompletedException} 선례 정합).
 */
public class OrderNotPendingPaymentException extends RuntimeException {

    public OrderNotPendingPaymentException(String message) {
        super(message);
    }
}

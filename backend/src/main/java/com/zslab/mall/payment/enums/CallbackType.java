package com.zslab.mall.payment.enums;

/**
 * PG 콜백 타입(D-34·3값). PG가 결제 결과를 통지하는 콜백의 종류다.
 *
 * <p>콜백 타입 × 현재 PaymentStatus 조합으로 처리가 결정된다(D-34 매트릭스). 처리 주체는
 * {@link com.zslab.mall.payment.service.PaymentService}이며, 본 enum은 콜백 분류값만 담는다.
 * DB 컬럼이 아니라 콜백 요청 파라미터다(payment 테이블 미저장).
 */
public enum CallbackType {
    /** 결제 성공 통지. */
    SUCCESS,
    /** 결제 실패 통지. */
    FAILURE,
    /** 결제 취소 통지. */
    CANCEL
}

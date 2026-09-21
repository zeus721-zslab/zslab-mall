package com.zslab.mall.payment.exception;

/**
 * 같은 (pg_provider, pg_tid)로 이미 다른 결제 행이 존재할 때 발생한다(Track 93 D-198·409). {@code uk_payment_provider_pg_tid}(V3·PAY-3b·D-31)
 * 위반을 flush 시점 {@code DataIntegrityViolationException}에서 해당 제약만 판별해 변환한다(90-C {@code uk_product_variant_options} 선례).
 * GlobalExceptionHandler가 409로 매핑한다.
 */
public class PaymentPgTidConflictException extends RuntimeException {
    public PaymentPgTidConflictException(String message) {
        super(message);
    }
}

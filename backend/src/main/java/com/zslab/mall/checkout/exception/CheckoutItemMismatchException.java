package com.zslab.mall.checkout.exception;

/**
 * 체크아웃 품목의 variant가 지정 product에 속하지 않을 때 발생한다(D-64 정합성 검증·가격 조작 차단).
 * 형식은 맞으나 조합이 무효한 비즈니스 규칙 위반이므로 전역 예외 핸들러가 HTTP 422로 응답한다(§16).
 */
public class CheckoutItemMismatchException extends RuntimeException {

    public CheckoutItemMismatchException(String message) {
        super(message);
    }
}

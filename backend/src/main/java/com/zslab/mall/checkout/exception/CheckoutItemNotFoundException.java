package com.zslab.mall.checkout.exception;

/**
 * 체크아웃 요청이 참조한 상품/변형 public_id가 존재하지 않을 때 발생한다(D-64 존재 확인). 전역 예외 핸들러가 HTTP 404로 응답한다.
 */
public class CheckoutItemNotFoundException extends RuntimeException {

    public CheckoutItemNotFoundException(String message) {
        super(message);
    }
}

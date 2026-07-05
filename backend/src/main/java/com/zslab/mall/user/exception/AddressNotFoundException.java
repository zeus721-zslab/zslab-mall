package com.zslab.mall.user.exception;

/**
 * 지정한 배송지가 존재하지 않거나 요청자 소유가 아닐 때 발생한다(Track 58 BL-4). 소유권 미충족도 동일 404로 은닉해
 * 존재 여부를 노출하지 않는다(CartItemNotFoundException 선례). 전역 예외 핸들러가 HTTP 404로 응답한다.
 */
public class AddressNotFoundException extends RuntimeException {

    public AddressNotFoundException(String message) {
        super(message);
    }
}

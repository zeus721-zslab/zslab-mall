package com.zslab.mall.user.exception;

/**
 * 지정한 User가 존재하지 않을 때 발생한다(Track 37 판매자 provisioning의 owner 검증 등). 전역 예외 핸들러가 HTTP 404로 응답한다.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String message) {
        super(message);
    }
}

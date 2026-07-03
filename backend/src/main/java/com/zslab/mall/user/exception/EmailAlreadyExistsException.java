package com.zslab.mall.user.exception;

/**
 * 이미 사용 중인 이메일로 회원가입을 시도할 때 발생한다(Track 34). 전역 예외 핸들러가 HTTP 409(CONFLICT)로 응답한다.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}

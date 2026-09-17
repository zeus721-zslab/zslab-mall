package com.zslab.mall.user.exception;

/**
 * 연락처(phone)가 없어 SMS를 보낼 수 없는 회원의 임시 비밀번호 발급 시도(Track 84). 전역 예외 핸들러가 HTTP 422로 응답한다.
 */
public class MemberPhoneMissingException extends RuntimeException {
    public MemberPhoneMissingException(String message) {
        super(message);
    }
}

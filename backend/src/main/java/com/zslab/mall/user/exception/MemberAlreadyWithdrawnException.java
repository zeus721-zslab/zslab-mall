package com.zslab.mall.user.exception;

/**
 * 이미 탈퇴한 회원에 대한 수정·탈퇴·임시 비밀번호 발급 시도(Track 84). 전역 예외 핸들러가 HTTP 409로 응답한다.
 */
public class MemberAlreadyWithdrawnException extends RuntimeException {
    public MemberAlreadyWithdrawnException(String message) {
        super(message);
    }
}

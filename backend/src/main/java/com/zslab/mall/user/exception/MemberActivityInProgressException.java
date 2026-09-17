package com.zslab.mall.user.exception;

/**
 * 진행 중 주문·활성 클레임이 있는 회원의 탈퇴 시도(Track 84). 전역 예외 핸들러가 HTTP 409로 응답한다.
 */
public class MemberActivityInProgressException extends RuntimeException {
    public MemberActivityInProgressException(String message) {
        super(message);
    }
}

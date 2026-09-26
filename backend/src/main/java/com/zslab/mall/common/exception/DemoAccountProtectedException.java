package com.zslab.mall.common.exception;

/**
 * 데모 계정(보호 대상 이메일)의 로그인을 깨뜨릴 수 있는 조작(비밀번호 변경·탈퇴·역할 해제·셀러 구성원 제외)을 요청했을 때 발생한다(D-230).
 * 요청자가 누구든 같다. 전역 예외 핸들러가 HTTP 403 {@code DEMO_ACCOUNT_PROTECTED}로 응답한다.
 */
public class DemoAccountProtectedException extends RuntimeException {

    public DemoAccountProtectedException(String message) {
        super(message);
    }
}

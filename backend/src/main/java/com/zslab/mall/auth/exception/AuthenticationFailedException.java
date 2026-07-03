package com.zslab.mall.auth.exception;

/**
 * 로그인 인증 실패(미존재·비활성·비번 불일치·role 부적격 통합). 전역 예외 핸들러가 HTTP 401 {@code AUTHENTICATION_FAILED}로
 * 응답하며, 외부 메시지는 사유 무관 "Invalid email or password."로 통일한다(계정 열거·자격 노출 방지·Track 33).
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Invalid email or password.");
    }
}

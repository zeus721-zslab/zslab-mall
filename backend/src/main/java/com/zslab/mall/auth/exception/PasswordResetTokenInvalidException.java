package com.zslab.mall.auth.exception;

/**
 * 비밀번호 재설정 토큰 무효(D-269). 없음·만료·이미 사용·대상 부적격을 구분하지 않고 같은 400 {@code PASSWORD_RESET_TOKEN_INVALID}로 응답한다
 * (토큰 상태를 외부에 알려 줄 이유가 없다 — 사유는 서버 로그로만 남긴다).
 */
public class PasswordResetTokenInvalidException extends RuntimeException {

    public PasswordResetTokenInvalidException() {
        super("재설정 링크가 만료되었거나 이미 사용되었습니다. 비밀번호 재설정을 다시 요청해 주세요.");
    }
}

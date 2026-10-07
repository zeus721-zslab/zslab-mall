package com.zslab.mall.auth.controller.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 요청(D-269). 형식 검증만 하고 가입 여부는 응답에 드러내지 않는다.
 *
 * @param email 재설정 링크를 받을 이메일(user.email VARCHAR(254))
 */
public record PasswordResetRequest(
        @NotBlank @Email @Size(max = 254) String email) {

    /** 요청 로그·예외 메시지에 이메일을 남기지 않는다. */
    @Override
    public String toString() {
        return "PasswordResetRequest[email=****]";
    }
}

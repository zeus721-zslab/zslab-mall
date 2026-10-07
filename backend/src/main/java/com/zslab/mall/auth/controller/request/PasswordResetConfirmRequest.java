package com.zslab.mall.auth.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 확정(D-269). newPassword 길이 규칙은 ChangePasswordRequest·PasswordPolicy와 같다(72바이트 상한은 PasswordPolicy).
 *
 * @param token       메일 링크의 토큰 원문(base64url 43자)
 * @param newPassword 새 비밀번호
 */
public record PasswordResetConfirmRequest(
        @NotBlank @Size(max = 100) String token,
        @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.") String newPassword) {

    /** 토큰·비밀번호 평문이 로그·예외 메시지로 새지 않게 마스킹한다(D-204 N3와 같은 방어). */
    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[token=****, newPassword=****]";
    }
}

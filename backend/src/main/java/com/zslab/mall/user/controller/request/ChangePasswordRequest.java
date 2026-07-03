package com.zslab.mall.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 요청(Track 34). 현재 비밀번호 확인 후 새 비밀번호로 교체한다.
 *
 * <p>newPassword 최소·최대 길이는 SignupRequest·{@link com.zslab.mall.user.policy.PasswordPolicy}와 동일 규칙이다.
 * max=72는 BCrypt 유효 입력 상한(초과 bytes 무시) 방어(가입과 동일 SoT).
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.") String newPassword) {
}

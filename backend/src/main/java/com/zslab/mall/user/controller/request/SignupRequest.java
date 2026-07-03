package com.zslab.mall.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청(Track 34·Buyer 셀프가입). email·name·phone·password.
 *
 * <p>email은 기존 {@code @Email} 미사용 컨벤션에 따라 {@code @Pattern}으로 형식 검증한다.
 * password 최소 길이는 {@link com.zslab.mall.user.policy.PasswordPolicy}와 동일 규칙이다(형식+서비스 이중).
 */
public record SignupRequest(
        @NotBlank
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 254) // SoT: User.email @Column(length=254)
        String email,
        @NotBlank @Size(max = 50) String name, // SoT: User.name @Column(length=50)
        @NotBlank @Size(max = 20) String phone, // SoT: User.phone @Column(length=20)
        // password는 BCrypt hash로 저장돼 raw 상한은 DB 무관. max=72는 BCrypt 유효 입력 상한(초과 bytes 무시) 방어.
        @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.") String password) {
}

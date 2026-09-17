package com.zslab.mall.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원 프로필 수정 요청(Track 58 BL-3). name·phone 교체. email은 로그인 자격증명이라 수정 대상 아님(별 요구).
 *
 * <p>@Size 상한은 {@code SignupRequest}·User @Column length와 동일 SoT. phone 형식은 관리자 수정
 * ({@link AdminMemberUpdateRequest#PHONE_PATTERN})과 동일하게 강제한다(Track 84·SMS 수신처).
 */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 50) String name, // SoT: User.name @Column(length=50)
        @NotBlank @Size(max = 20)
        @Pattern(regexp = AdminMemberUpdateRequest.PHONE_PATTERN, message = "휴대폰 번호 형식이 올바르지 않습니다(예: 010-1234-5678).")
        String phone) { // SoT: User.phone @Column(length=20)
}

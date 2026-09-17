package com.zslab.mall.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 회원 정보 수정 요청(Track 84). name·phone 교체. 제약은 {@link UpdateProfileRequest}·User @Column length와 동일 SoT이며
 * phone은 SMS 수신처라 휴대폰 형식(하이픈 선택)을 강제한다.
 */
public record AdminMemberUpdateRequest(
        @NotBlank @Size(max = 50) String name, // SoT: User.name @Column(length=50)
        @NotBlank @Size(max = 20)
        @Pattern(regexp = PHONE_PATTERN, message = "휴대폰 번호 형식이 올바르지 않습니다(예: 010-1234-5678).")
        String phone) { // SoT: User.phone @Column(length=20)

    /** 국내 휴대폰(010·011·016·017·018·019)·하이픈 선택. {@link UpdateProfileRequest}와 공유. */
    public static final String PHONE_PATTERN = "^01[016789]-?\\d{3,4}-?\\d{4}$";
}

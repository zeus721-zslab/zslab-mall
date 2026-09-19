package com.zslab.mall.seller.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 미가입자 구성원 등록용 신규 계정 정보(Track 89-G). 필드·검증은 셀프 가입 {@code SignupRequest}와 같다(비밀번호만 임시 발급으로 대체).
 * phone은 임시 비밀번호 SMS 수신처라 필수다.
 */
public record AdminSellerMemberNewUserRequest(
        @NotBlank
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 254) String email, // SoT: User.email @Column(length=254)
        @NotBlank @Size(max = 50) String name, // SoT: User.name @Column(length=50)
        @NotBlank @Size(max = 20) String phone) { // SoT: User.phone @Column(length=20)
}

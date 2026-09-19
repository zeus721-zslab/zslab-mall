package com.zslab.mall.user.service;

/** 관리자 주도 계정 생성 입력(Track 89-G). 형식 검증(@NotBlank·이메일 @Pattern·길이)은 호출측 DTO가 끝낸 값이다. */
public record AdminMemberProvisionCommand(String email, String name, String phone) {
}

package com.zslab.mall.user.controller.response;

/** 회원가입 응답. 생성된 회원 public_id만 노출(민감정보·passwordHash 절대 제외). (Track 34) */
public record SignupResponse(String userPublicId) {
}

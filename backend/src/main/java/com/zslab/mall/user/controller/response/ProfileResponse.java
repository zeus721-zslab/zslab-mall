package com.zslab.mall.user.controller.response;

/** 회원 프로필 조회·수정 응답(Track 58 BL-3). publicId·email·name·phone. passwordHash 등 민감정보 제외. */
public record ProfileResponse(String publicId, String email, String name, String phone) {
}

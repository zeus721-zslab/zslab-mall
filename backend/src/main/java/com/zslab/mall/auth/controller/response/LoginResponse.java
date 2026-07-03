package com.zslab.mall.auth.controller.response;

/** 로그인 응답. 토큰 문자열만 노출(매체 중립·민감정보 미포함). (Track 33) */
public record LoginResponse(String token) {
}

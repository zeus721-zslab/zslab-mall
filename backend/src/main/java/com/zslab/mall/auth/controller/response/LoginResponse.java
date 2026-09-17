package com.zslab.mall.auth.controller.response;

/**
 * 로그인 응답. 토큰 문자열(매체 중립·민감정보 미포함) + 비밀번호 변경 강제 플래그(Track 84·임시 비밀번호 발급 회원은 true·강제는 FE 담당).
 * (Track 33)
 */
public record LoginResponse(String token, boolean passwordChangeRequired) {
}

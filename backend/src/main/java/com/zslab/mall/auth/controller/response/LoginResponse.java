package com.zslab.mall.auth.controller.response;

/**
 * 로그인 응답. 비밀번호 변경 강제 플래그(Track 84·임시 비밀번호 발급 회원은 true·강제는 FE 담당)만 싣는다. 토큰은 역할 HttpOnly 쿠키로만
 * 전달한다(D-235 PR3 K2).
 */
public record LoginResponse(boolean passwordChangeRequired) {
}

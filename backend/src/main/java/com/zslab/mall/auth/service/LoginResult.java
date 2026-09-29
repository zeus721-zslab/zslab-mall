package com.zslab.mall.auth.service;

/**
 * 로그인 서비스 결과(D-235 PR3 K2). 토큰은 컨트롤러가 역할 HttpOnly 쿠키로만 내보내고 응답 본문에는 싣지 않는다.
 *
 * @param token 발급한 액세스 토큰(쿠키 값)
 * @param passwordChangeRequired 임시 비밀번호 발급 회원이면 true(Track 84·강제는 FE 담당)
 */
public record LoginResult(String token, boolean passwordChangeRequired) {

    /** 토큰이 로그에 남지 않도록 값은 싣지 않는다. */
    @Override
    public String toString() {
        return "LoginResult[token=***, passwordChangeRequired=" + passwordChangeRequired + "]";
    }
}

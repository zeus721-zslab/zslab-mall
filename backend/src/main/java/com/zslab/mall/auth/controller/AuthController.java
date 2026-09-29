package com.zslab.mall.auth.controller;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매자 인증 보조 REST 컨트롤러(Track 33·D-235). 인증 전 CSRF 토큰 발급과 구매자 로그아웃을 노출한다(SecurityConfig permitAll).
 * 로그인은 역할 경로({@link RoleAuthController})에만 있다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthCookies authCookies;

    public AuthController(AuthCookies authCookies) {
        this.authCookies = authCookies;
    }

    /**
     * 인증 전 CSRF 토큰 발급(D-235 PR3 K7). 요청에 XSRF-TOKEN 쿠키가 없으면 CsrfFilter가 응답에 Set-Cookie를 단다 — 핸들러는 본문 없이 204만
     * 준다(ResponseEntity 헤더로 Set-Cookie를 주면 그 XSRF Set-Cookie가 교체되므로 헤더를 싣지 않는다).
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    /** 구매자 로그아웃. 구매자 쿠키만 만료(204·addHeader 발급 — PR3 K8). 쿠키 인증 요청이면 CSRF 토큰이 필요하다(SecurityConfig). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        return RoleAuthController.logout(authCookies, ActorRole.BUYER, response);
    }
}

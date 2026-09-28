package com.zslab.mall.auth.controller;

import com.zslab.mall.auth.controller.request.LoginRequest;
import com.zslab.mall.auth.controller.response.LoginResponse;
import com.zslab.mall.auth.service.AuthService;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 REST 컨트롤러(Track 33). 로그인 endpoint를 노출한다. 인증 전 접근이므로 SecurityConfig에서 permitAll.
 *
 * <p>HTTP 책임만 가진다: 요청 검증·Service 위임·HTTP 변환. 자격 검증·토큰 발급은 {@link AuthService} 책임.
 * 이 로그인은 쿠키를 발급하지 않는다(D-235 확장-수축: 기존 클라이언트 경로 무변경·PR3에서 제거). 역할 쿠키는 {@link RoleAuthController}의
 * 역할 로그인에서만 나온다. 구매자 로그아웃은 구매자 쿠키만 만료시킨다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookies authCookies;

    public AuthController(AuthService authService, AuthCookies authCookies) {
        this.authService = authService;
        this.authCookies = authCookies;
    }

    /** 로그인. 성공 200 + 토큰. 실패는 401 "Invalid email or password."({@link AuthService}). */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** 구매자 로그아웃. 구매자 쿠키만 만료(204). 쿠키 인증 요청이면 CSRF 토큰이 필요하다(SecurityConfig). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookies.expire(ActorRole.BUYER).toString())
                .build();
    }
}

package com.zslab.mall.auth.controller;

import com.zslab.mall.auth.controller.request.LoginRequest;
import com.zslab.mall.auth.controller.response.LoginResponse;
import com.zslab.mall.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 REST 컨트롤러(Track 33). 로그인 endpoint를 노출한다. 인증 전 접근이므로 SecurityConfig에서 permitAll.
 *
 * <p>HTTP 책임만 가진다: 요청 검증·Service 위임·HTTP 변환. 자격 검증·토큰 발급은 {@link AuthService} 책임.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 로그인. 성공 200 + 토큰. 실패는 401 "Invalid email or password."({@link AuthService}). */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}

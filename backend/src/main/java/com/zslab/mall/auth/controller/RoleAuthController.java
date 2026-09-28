package com.zslab.mall.auth.controller;

import com.zslab.mall.auth.controller.request.LoginRequest;
import com.zslab.mall.auth.controller.request.RoleLoginRequest;
import com.zslab.mall.auth.controller.response.LoginResponse;
import com.zslab.mall.auth.service.AuthService;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 역할 쿠키 로그인(구매자·셀러·관리자)과 셀러·관리자 로그아웃(D-235). 역할을 경로로 고정해 {@link AuthService#login}을 재사용하므로 역할
 * 판정·실패 매핑(401 AUTHENTICATION_FAILED)·셀러 상태 검사는 기존 로그인과 같다. 성공하면 그 역할 쿠키만 발급하고, 로그아웃은 그 역할 쿠키만
 * 만료시킨다. 모든 경로가 SecurityConfig에서 permitAll(셀러·관리자는 역할 hasRole 규칙보다 앞)이고 로그인은 CSRF 면제다.
 */
@RestController
public class RoleAuthController {

    private final AuthService authService;
    private final AuthCookies authCookies;

    public RoleAuthController(AuthService authService, AuthCookies authCookies) {
        this.authService = authService;
        this.authCookies = authCookies;
    }

    // 구매자 쿠키 로그인은 기존 /api/v1/auth/login(본문 role·쿠키 없음)을 바꾸지 않기 위해 따로 둔다(D-235 확장-수축).
    @PostMapping("/api/v1/auth/buyer/login")
    public ResponseEntity<LoginResponse> buyerLogin(@RequestBody @Valid RoleLoginRequest request) {
        return login(request, ActorRole.BUYER);
    }

    @PostMapping("/api/v1/seller/auth/login")
    public ResponseEntity<LoginResponse> sellerLogin(@RequestBody @Valid RoleLoginRequest request) {
        return login(request, ActorRole.SELLER);
    }

    @PostMapping("/api/v1/admin/auth/login")
    public ResponseEntity<LoginResponse> adminLogin(@RequestBody @Valid RoleLoginRequest request) {
        return login(request, ActorRole.ADMIN);
    }

    @PostMapping("/api/v1/seller/auth/logout")
    public ResponseEntity<Void> sellerLogout() {
        return logout(ActorRole.SELLER);
    }

    @PostMapping("/api/v1/admin/auth/logout")
    public ResponseEntity<Void> adminLogout() {
        return logout(ActorRole.ADMIN);
    }

    private ResponseEntity<LoginResponse> login(RoleLoginRequest request, ActorRole role) {
        LoginResponse response = authService.login(new LoginRequest(request.email(), request.password(), role));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookies.issue(role, response.token()).toString())
                .body(response);
    }

    private ResponseEntity<Void> logout(ActorRole role) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookies.expire(role).toString())
                .build();
    }
}

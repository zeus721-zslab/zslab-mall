package com.zslab.mall.auth.controller;

import com.zslab.mall.auth.controller.request.LoginRequest;
import com.zslab.mall.auth.controller.response.LoginResponse;
import com.zslab.mall.auth.service.AuthService;
import com.zslab.mall.auth.service.LoginResult;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 역할 쿠키 로그인(구매자·셀러·관리자)과 셀러·관리자 로그아웃(D-235). 역할을 경로로 고정해 {@link AuthService#login}에 넘기므로 역할
 * 판정·실패 매핑(401 AUTHENTICATION_FAILED)·셀러 상태 검사는 한 곳이다. 성공하면 그 역할 쿠키만 발급하고(본문은 passwordChangeRequired만),
 * 로그아웃은 그 역할 쿠키만 만료시킨다. 모든 경로가 SecurityConfig에서 permitAll(셀러·관리자는 역할 hasRole 규칙보다 앞)이고, 로그인은 인증 전
 * CSRF 토큰(GET /api/v1/auth/csrf)을 검증한다(PR3 K7).
 */
@RestController
public class RoleAuthController {

    private final AuthService authService;
    private final AuthCookies authCookies;

    public RoleAuthController(AuthService authService, AuthCookies authCookies) {
        this.authService = authService;
        this.authCookies = authCookies;
    }

    @PostMapping("/api/v1/auth/buyer/login")
    public ResponseEntity<LoginResponse> buyerLogin(@RequestBody @Valid LoginRequest request, HttpServletResponse response) {
        return login(request, ActorRole.BUYER, response);
    }

    @PostMapping("/api/v1/seller/auth/login")
    public ResponseEntity<LoginResponse> sellerLogin(@RequestBody @Valid LoginRequest request, HttpServletResponse response) {
        return login(request, ActorRole.SELLER, response);
    }

    @PostMapping("/api/v1/admin/auth/login")
    public ResponseEntity<LoginResponse> adminLogin(@RequestBody @Valid LoginRequest request, HttpServletResponse response) {
        return login(request, ActorRole.ADMIN, response);
    }

    @PostMapping("/api/v1/seller/auth/logout")
    public ResponseEntity<Void> sellerLogout(HttpServletResponse response) {
        return logout(authCookies, ActorRole.SELLER, response);
    }

    @PostMapping("/api/v1/admin/auth/logout")
    public ResponseEntity<Void> adminLogout(HttpServletResponse response) {
        return logout(authCookies, ActorRole.ADMIN, response);
    }

    /**
     * 역할 쿠키는 서블릿 응답에 addHeader로 더한다(D-235 개정 2·PR3 K8). ResponseEntity 헤더로 주면 Spring이 Set-Cookie를 통째로 교체해
     * CsrfFilter가 먼저 단 XSRF-TOKEN Set-Cookie가 사라진다.
     */
    private ResponseEntity<LoginResponse> login(LoginRequest request, ActorRole role, HttpServletResponse servletResponse) {
        LoginResult result = authService.login(request, role);
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, authCookies.issue(role, result.token()).toString());
        return ResponseEntity.ok(new LoginResponse(result.passwordChangeRequired()));
    }

    /** 역할 쿠키 만료(204). 구매자 로그아웃({@link AuthController})도 같은 발급 방식을 쓴다. */
    static ResponseEntity<Void> logout(AuthCookies authCookies, ActorRole role, HttpServletResponse servletResponse) {
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, authCookies.expire(role).toString());
        return ResponseEntity.noContent().build();
    }
}

package com.zslab.mall.auth.controller;

import com.zslab.mall.auth.controller.request.PasswordResetConfirmRequest;
import com.zslab.mall.auth.controller.request.PasswordResetRequest;
import com.zslab.mall.auth.controller.response.PasswordResetAvailabilityResponse;
import com.zslab.mall.auth.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매자 비밀번호 재설정 공개 API(D-269·SecurityConfig {@code /api/v1/auth/**} permitAll). 요청은 대상·발송 여부와 무관하게 항상 202다.
 */
@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /** 사용 가능 여부(실 메일 발송이 켜져 있는지). FE 로그인 화면의 "비밀번호 찾기" 분기용. */
    @GetMapping("/availability")
    public ResponseEntity<PasswordResetAvailabilityResponse> availability() {
        return ResponseEntity.ok(new PasswordResetAvailabilityResponse(passwordResetService.isEnabled()));
    }

    /** 재설정 메일 요청. 가입 여부·역할·발송 결과와 무관하게 202(본문 없음·이메일 열거 방지). */
    @PostMapping("/request")
    public ResponseEntity<Void> request(@RequestBody @Valid PasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

    /** 재설정 확정. 성공 204 · 토큰 무효 400 PASSWORD_RESET_TOKEN_INVALID · 비밀번호 규칙 위반 400. */
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestBody @Valid PasswordResetConfirmRequest request) {
        passwordResetService.confirm(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}

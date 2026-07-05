package com.zslab.mall.user.controller;

import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.user.controller.request.ChangePasswordRequest;
import com.zslab.mall.user.controller.request.SignupRequest;
import com.zslab.mall.user.controller.request.UpdateProfileRequest;
import com.zslab.mall.user.controller.response.ProfileResponse;
import com.zslab.mall.user.controller.response.SignupResponse;
import com.zslab.mall.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 REST 컨트롤러(Track 34). Buyer 셀프가입 endpoint를 노출한다. 인증 전 접근이므로 SecurityConfig에서 permitAll.
 *
 * <p>HTTP 책임만 가진다: 요청 검증·Service 위임·HTTP 변환. 가입 로직·중복 검증·role 배선은 {@link UserService} 책임.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    public UserController(UserService userService, AuthenticatedUserResolver authenticatedUserResolver) {
        this.userService = userService;
        this.authenticatedUserResolver = authenticatedUserResolver;
    }

    /** Buyer 셀프가입. 성공 201 + userPublicId. 중복 409·검증 실패 400({@link UserService}·GlobalExceptionHandler). */
    @PostMapping
    public ResponseEntity<SignupResponse> signup(@RequestBody @Valid SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    /** 본인 비밀번호 변경(Track 34). 인증 필수·현재 비번 확인 후 교체. 성공 204·현재 비번 불일치 400·정책 위반 400. */
    @PatchMapping("/me/password")
    public ResponseEntity<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        Long userId = authenticatedUserResolver.requireUserId();
        userService.changePassword(userId, request);
        return ResponseEntity.noContent().build();
    }

    /** 본인 프로필 조회(Track 58 BL-3). 인증 필수. 성공 200 + ProfileResponse. */
    @GetMapping("/me")
    public ResponseEntity<ProfileResponse> getMyProfile() {
        Long userId = authenticatedUserResolver.requireUserId();
        return ResponseEntity.ok(userService.getMyProfile(userId));
    }

    /** 본인 프로필 수정(Track 58 BL-3). 인증 필수·name·phone 교체. 성공 200 + 수정 후 ProfileResponse·검증 실패 400. */
    @PatchMapping("/me")
    public ResponseEntity<ProfileResponse> updateMyProfile(@RequestBody @Valid UpdateProfileRequest request) {
        Long userId = authenticatedUserResolver.requireUserId();
        return ResponseEntity.ok(userService.updateMyProfile(userId, request));
    }
}

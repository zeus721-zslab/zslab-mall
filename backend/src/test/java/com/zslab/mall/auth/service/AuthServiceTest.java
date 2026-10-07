package com.zslab.mall.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.auth.controller.request.LoginRequest;
import com.zslab.mall.auth.exception.AuthenticationFailedException;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.RoleAuthorization;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 로그인 실패 분기의 비밀번호 비교 비용 균일화(PF-14). 응답 시간 측정은 흔들림이 커서 쓰지 않고, 미존재·비활성·탈퇴 분기에서도
 * 더미 해시와 {@code matches}가 호출되는지로 검증한다(호출되지 않으면 BCrypt 비용만큼 응답이 빨라져 가입 여부가 샌다).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "pf14@zslab.test";
    private static final String PASSWORD = "submitted-password";
    private static final String DUMMY_HASH = "dummy-hash";
    private static final String REAL_HASH = "real-hash";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RoleAuthorization roleAuthorization;
    @Mock
    private TokenProvider tokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        lenient().when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        authService = new AuthService(userRepository, passwordEncoder, roleAuthorization, tokenProvider);
    }

    @Test
    @DisplayName("없는 이메일 → 더미 해시와 matches 1회 후 401 예외")
    void login_unknownEmail_comparesWithDummyHash() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request(), ActorRole.BUYER))
                .isInstanceOf(AuthenticationFailedException.class);
        verify(passwordEncoder, times(1)).matches(PASSWORD, DUMMY_HASH);
    }

    @Test
    @DisplayName("탈퇴 회원 → 더미 해시와 matches 1회 · 실제 해시 비교 없음 · 401 예외")
    void login_withdrawnUser_comparesWithDummyHash() {
        User user = User.create(EMAIL, "탈퇴회원", "010-0000-0000");
        user.assignPasswordHash(REAL_HASH);
        user.withdraw();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(request(), ActorRole.BUYER))
                .isInstanceOf(AuthenticationFailedException.class);
        verify(passwordEncoder, times(1)).matches(PASSWORD, DUMMY_HASH);
        verify(passwordEncoder, never()).matches(PASSWORD, REAL_HASH);
    }

    @Test
    @DisplayName("비밀번호 없는(비활성) 계정 → 더미 해시와 matches 1회 후 401 예외")
    void login_noPasswordHash_comparesWithDummyHash() {
        User user = User.create(EMAIL, "비활성회원", "010-0000-0000");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(request(), ActorRole.BUYER))
                .isInstanceOf(AuthenticationFailedException.class);
        verify(passwordEncoder, times(1)).matches(PASSWORD, DUMMY_HASH);
    }

    private static LoginRequest request() {
        return new LoginRequest(EMAIL, PASSWORD, null);
    }
}

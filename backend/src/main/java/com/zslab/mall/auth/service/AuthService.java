package com.zslab.mall.auth.service;

import com.zslab.mall.auth.controller.request.LoginRequest;
import com.zslab.mall.auth.controller.response.LoginResponse;
import com.zslab.mall.auth.exception.AuthenticationFailedException;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.RoleAuthorization;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증(로그인) 서비스. (Track 33)
 *
 * <p>이메일·비밀번호·role을 검증해 토큰을 발급한다. 실패 사유(미존재·비활성·비번 불일치·role 부적격)는 내부 로그로만
 * 구분하고, 외부로는 사유를 노출하지 않기 위해 모두 동일한 {@link AuthenticationFailedException}(401 "Invalid email or
 * password.")으로 던진다(계정 열거·자격 노출 방지). 이메일·비밀번호 평문은 로그에 남기지 않는다(actorId·사유코드만).
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleAuthorization roleAuthorization;
    private final TokenProvider tokenProvider;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RoleAuthorization roleAuthorization,
            TokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.roleAuthorization = roleAuthorization;
        this.tokenProvider = tokenProvider;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // @SQLRestriction("deleted_at IS NULL")로 소프트삭제 회원은 조회 자체가 제외된다(→ USER_NOT_FOUND 경로).
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> fail(null, "USER_NOT_FOUND"));

        if (user.getPasswordHash() == null || user.getWithdrawnAt() != null) {
            throw fail(user.getId(), "ACCOUNT_DISABLED");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw fail(user.getId(), "PASSWORD_MISMATCH");
        }
        ActorRole role = request.role();
        if (!roleAuthorization.isAuthorized(user.getId(), role)) {
            throw fail(user.getId(), "ROLE_MISMATCH");
        }
        return new LoginResponse(tokenProvider.issue(user.getId(), role));
    }

    /** 실패 사유는 내부 로그로만 구분(이메일·비번 평문 미기록·actorId·사유코드만)하고 외부는 동일 예외로 통일한다. */
    private AuthenticationFailedException fail(Long actorId, String reasonCode) {
        log.warn("[Auth] 로그인 실패 actorId={} reason={}", actorId, reasonCode);
        return new AuthenticationFailedException();
    }
}

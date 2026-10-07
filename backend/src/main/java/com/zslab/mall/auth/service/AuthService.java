package com.zslab.mall.auth.service;

import com.zslab.mall.auth.controller.request.LoginRequest;
import com.zslab.mall.auth.exception.AuthenticationFailedException;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.RoleAuthorization;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증(로그인) 서비스. (Track 33)
 *
 * <p>이메일·비밀번호와 로그인 경로의 role을 검증해 토큰을 발급한다. 실패 사유(미존재·비활성·비번 불일치·role 부적격)는 내부 로그로만
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
    /**
     * 비교 대상이 없는 실패 분기(미존재·비활성·탈퇴)에서도 비밀번호 비교 비용을 치르기 위한 해시(PF-14). 응답 시간 차로 가입 여부가 새지 않게 한다.
     * 실제 인코더로 만들어 인코더 강도가 바뀌어도 비용이 같다. 평문은 기동마다 임의값이라 어떤 계정과도 대응하지 않는다.
     */
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RoleAuthorization roleAuthorization,
            TokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.roleAuthorization = roleAuthorization;
        this.tokenProvider = tokenProvider;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * @param role 로그인 경로가 고정한 역할(구매자·셀러·관리자 로그인 · D-235 PR3 K3)
     * @throws AuthenticationFailedException 미존재·비활성·비밀번호 불일치·역할 부적격(사유 비노출·401)
     */
    @Transactional(readOnly = true)
    public LoginResult login(LoginRequest request, ActorRole role) {
        // @SQLRestriction("deleted_at IS NULL")로 소프트삭제 회원은 조회 자체가 제외된다(→ USER_NOT_FOUND 경로).
        Optional<User> found = userRepository.findByEmail(request.email());
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyPasswordHash); // PF-14: 결과는 버린다 — 비교 비용만 맞춘다
            throw fail(null, "USER_NOT_FOUND");
        }
        User user = found.get();

        if (user.getPasswordHash() == null || user.getWithdrawnAt() != null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash); // PF-14: 위와 같은 이유
            throw fail(user.getId(), "ACCOUNT_DISABLED");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw fail(user.getId(), "PASSWORD_MISMATCH");
        }
        if (!roleAuthorization.isAuthorized(user.getId(), role)) {
            throw fail(user.getId(), "ROLE_MISMATCH");
        }
        // 데모 표식은 관리자 토큰에만 심는다(최종 점검 K1). 표식은 권한을 줄이기만 하므로 자기 신고를 그대로 받는다.
        boolean publicDemo = role == ActorRole.ADMIN && Boolean.TRUE.equals(request.publicDemo());
        return new LoginResult(tokenProvider.issue(user.getId(), role, publicDemo), user.isPasswordChangeRequired());
    }

    /** 실패 사유는 내부 로그로만 구분(이메일·비번 평문 미기록·actorId·사유코드만)하고 외부는 동일 예외로 통일한다. */
    private AuthenticationFailedException fail(Long actorId, String reasonCode) {
        log.warn("[Auth] 로그인 실패 actorId={} reason={}", actorId, reasonCode);
        return new AuthenticationFailedException();
    }
}

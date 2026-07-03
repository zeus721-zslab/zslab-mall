package com.zslab.mall.user.service;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.user.controller.request.ChangePasswordRequest;
import com.zslab.mall.user.controller.request.SignupRequest;
import com.zslab.mall.user.controller.response.SignupResponse;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.exception.EmailAlreadyExistsException;
import com.zslab.mall.user.policy.PasswordPolicy;
import com.zslab.mall.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 Application Service(Track 34). Buyer 셀프가입을 담당한다. 트랜잭션 경계는 메서드 단위다.
 *
 * <p>가입 흐름: 비밀번호 정책 검증 → email 중복 검증 → User 생성·해시 저장 → BUYER role 매핑.
 * role 배선은 seed된 BUYER Role(V11)을 재사용한다. password 평문은 로그에 남기지 않는다(userId·publicId만).
 */
@Slf4j
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public UserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    /**
     * Buyer 셀프가입. 성공 시 회원과 BUYER role 매핑을 생성한다.
     *
     * @throws IllegalArgumentException 비밀번호 정책 위반 시(PasswordPolicy)
     * @throws EmailAlreadyExistsException 이미 사용 중인 이메일인 경우(409)
     * @throws IllegalStateException BUYER Role seed가 없는 경우(내부 오류·500)
     */
    public SignupResponse register(SignupRequest request) {
        passwordPolicy.validate(request.password());

        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("이미 사용 중인 이메일");
        }

        User user = User.create(request.email(), request.name(), request.phone());
        user.assignPasswordHash(passwordEncoder.encode(request.password()));
        User saved = userRepository.save(user);

        Role buyerRole = roleRepository.findByCode(RoleCode.BUYER)
                .orElseThrow(() -> new IllegalStateException("BUYER Role seed 누락(V11 마이그레이션 확인 필요)."));
        userRoleRepository.save(UserRole.create(saved.getId(), buyerRole));

        log.info("[User] 회원가입 완료 userId={} publicId={}", saved.getId(), saved.getPublicId());
        return new SignupResponse(saved.getPublicId());
    }

    /**
     * 비밀번호 변경(Track 34). 현재 비밀번호 확인 후 새 비밀번호 해시로 교체한다.
     *
     * @throws IllegalStateException userId에 해당하는 User가 없는 경우(인증됐으나 데이터 부재·내부 오류·500)
     * @throws IllegalArgumentException 현재 비밀번호 불일치·자격증명 미생성 계정인 경우(400)
     */
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 userId에 해당하는 User가 없습니다: " + userId));

        // hash null(자격증명 미생성)·불일치 모두 동일 400으로 통일(사유는 내부 로그로만 구분)
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("[User] 비밀번호 변경 실패 userId={} reason={}",
                    userId, user.getPasswordHash() == null ? "NO_CREDENTIAL" : "PASSWORD_MISMATCH");
            throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
        }

        passwordPolicy.validate(request.newPassword());
        user.assignPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        log.info("[User] 비밀번호 변경 완료 userId={}", userId);
    }
}

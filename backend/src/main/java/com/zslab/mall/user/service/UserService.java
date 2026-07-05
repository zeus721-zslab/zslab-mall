package com.zslab.mall.user.service;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.grade.repository.BuyerGradeRepository;
import com.zslab.mall.user.controller.request.ChangePasswordRequest;
import com.zslab.mall.user.controller.request.SignupRequest;
import com.zslab.mall.user.controller.request.UpdateProfileRequest;
import com.zslab.mall.user.controller.response.ProfileResponse;
import com.zslab.mall.user.controller.response.SignupResponse;
import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.enums.GradeSource;
import com.zslab.mall.user.exception.EmailAlreadyExistsException;
import com.zslab.mall.user.policy.PasswordPolicy;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import com.zslab.mall.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 Application Service(Track 34). Buyer 셀프가입을 담당한다. 트랜잭션 경계는 메서드 단위다.
 *
 * <p>가입 흐름: 비밀번호 정책 검증 → email 중복 검증 → User 생성·해시 저장 → BUYER role 매핑 → 초기 BuyerProfile(SILVER·AUTO) 생성.
 * role 배선은 seed된 BUYER Role(V11)을, 초기 등급은 seed된 SILVER BuyerGrade(V15)를 재사용한다. password 평문은 로그에 남기지 않는다(userId·publicId만).
 */
@Slf4j
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final BuyerProfileRepository buyerProfileRepository;
    private final BuyerGradeRepository buyerGradeRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public UserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            BuyerProfileRepository buyerProfileRepository,
            BuyerGradeRepository buyerGradeRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.buyerProfileRepository = buyerProfileRepository;
        this.buyerGradeRepository = buyerGradeRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    /**
     * Buyer 셀프가입. 성공 시 회원·BUYER role 매핑·초기 BuyerProfile(SILVER·AUTO)을 생성한다.
     *
     * @throws IllegalArgumentException 비밀번호 정책 위반 시(PasswordPolicy)
     * @throws EmailAlreadyExistsException 이미 사용 중인 이메일인 경우(409)
     * @throws IllegalStateException BUYER Role seed 또는 SILVER BuyerGrade seed가 없는 경우(내부 오류·500)
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

        BuyerGrade silver = buyerGradeRepository.findByCode(BuyerGradeCode.SILVER)
                .orElseThrow(() -> new IllegalStateException("SILVER BuyerGrade seed 누락(V15 마이그레이션 확인 필요)."));
        buyerProfileRepository.save(BuyerProfile.create(saved, silver.getId(), GradeSource.AUTO));

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

    /**
     * 본인 프로필 조회(Track 58 BL-3). publicId·email·name·phone을 반환한다.
     *
     * @throws IllegalStateException userId에 해당하는 User가 없는 경우(인증됐으나 데이터 부재·내부 오류·500)
     */
    @Transactional(readOnly = true)
    public ProfileResponse getMyProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 userId에 해당하는 User가 없습니다: " + userId));
        return new ProfileResponse(user.getPublicId(), user.getEmail(), user.getName(), user.getPhone());
    }

    /**
     * 본인 프로필 수정(Track 58 BL-3). name·phone을 교체한다. email은 로그인 자격증명이라 변경하지 않는다.
     *
     * @throws IllegalStateException userId에 해당하는 User가 없는 경우(인증됐으나 데이터 부재·내부 오류·500)
     * @throws IllegalArgumentException name·phone이 null·blank인 경우(400·형식 검증은 DTO @Valid 선행)
     */
    public ProfileResponse updateMyProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 userId에 해당하는 User가 없습니다: " + userId));
        user.updateProfile(request.name(), request.phone());
        userRepository.save(user);

        log.info("[User] 프로필 수정 완료 userId={}", userId);
        return new ProfileResponse(user.getPublicId(), user.getEmail(), user.getName(), user.getPhone());
    }
}

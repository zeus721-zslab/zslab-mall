package com.zslab.mall.auth.bootstrap;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 SUPER_ADMIN 공급 부트스트랩(Track 38). 매 기동 실행되며, SUPER_ADMIN 보유 회원이 없을 때에 한해
 * {@code ADMIN_BOOTSTRAP_EMAIL}·{@code ADMIN_BOOTSTRAP_PASSWORD}로 SUPER_ADMIN 회원을 1회 생성한다.
 *
 * <p><b>매 기동 실행·enable flag 부재 이유</b>: ADMIN 게이트(/api/v1/admin/**)로 잠긴 시스템에서 최초 ADMIN은 API로
 * 만들 수 없다(admin이 없어 admin API 호출 불가·순환). 이 공급을 별도 enable flag 뒤에 두면 최초 기동에서 flag가 꺼진 채
 * 부팅될 때 영구히 admin이 없는 deadlock이 된다. 따라서 flag 없이 항상 실행하되, 이미 SUPER_ADMIN이 있으면 무작업(skip)해
 * 재기동에 안전하게 만든다.
 *
 * <p><b>생성 전용·수정 금지</b>: 이미 SUPER_ADMIN이 존재하면 email·password를 덮어쓰지 않는다(계정 복구 기능 아님).
 * 멱등 기준은 email이 아니라 "SUPER_ADMIN 역할 보유 회원 1명 이상"이다.
 *
 * <p><b>순서·Fail Fast</b>: 존재 확인을 먼저 한다 — SUPER_ADMIN이 있으면 env를 참조하지 않고 skip한다(env 미설정
 * 환경에서도 재기동 안전·"무작업" 계약 충족). SUPER_ADMIN이 없어 생성이 필요한 경로에서만 env 필수값을 검증하고, blank면
 * 즉시 예외로 기동을 중단한다(Fail Fast — admin 없는 시스템은 운영 불가이므로 조용한 부팅보다 큰 실패가 안전). 생성 도중
 * INSERT 실패도 {@code @Transactional}로 User·UserRole을 원자 롤백하고 예외를 전파한다(부분 생성 상태 방지).
 *
 * <p>email·password 평문은 어떤 로그에도 남기지 않는다.
 */
@Slf4j
@Component
public class SuperAdminBootstrapRunner implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public SuperAdminBootstrapRunner(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder,
            @Value("${admin.bootstrap.email:}") String bootstrapEmail,
            @Value("${admin.bootstrap.password:}") String bootstrapPassword) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    /**
     * 존재 확인(skip) → 부재 시 env 검증(Fail Fast) → User·UserRole 원자 생성. 전 과정을 단일 트랜잭션으로 묶어
     * 부분 생성(User만 저장되고 UserRole 실패)이 남지 않게 한다.
     *
     * @throws IllegalStateException 생성 필요 상태에서 필수 env가 blank이거나 SUPER_ADMIN Role seed가 없는 경우(Fail Fast)
     */
    @Override
    @Transactional
    public void run(String... args) {
        if (userRoleRepository.existsByRole_Code(RoleCode.SUPER_ADMIN)) {
            log.info("SUPER_ADMIN already exists. Bootstrap skipped.");
            return;
        }

        if (bootstrapEmail.isBlank() || bootstrapPassword.isBlank()) {
            throw new IllegalStateException(
                    "SUPER_ADMIN이 없어 최초 공급이 필요하나 ADMIN_BOOTSTRAP_EMAIL·ADMIN_BOOTSTRAP_PASSWORD env가 비어 있습니다.");
        }

        Role superAdminRole = roleRepository.findByCode(RoleCode.SUPER_ADMIN)
                .orElseThrow(() -> new IllegalStateException("SUPER_ADMIN Role seed 누락(V11 마이그레이션 확인 필요)."));

        User user = User.create(bootstrapEmail, null, null);
        user.assignPasswordHash(passwordEncoder.encode(bootstrapPassword));
        User saved = userRepository.save(user);
        userRoleRepository.save(UserRole.create(saved.getId(), superAdminRole));

        log.info("Bootstrap SUPER_ADMIN created.");
    }
}

package com.zslab.mall.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 최초 SUPER_ADMIN 부트스트랩 로직 통합 테스트(Track 38·실 MariaDB). {@link SuperAdminBootstrapRunner}를 테스트가 직접
 * 제어한 env(생성자 인자)와 DB 상태로 구성해 (A) 부재 시 생성 (B) 존재 시 멱등·무수정 invariant (C) 필수 env blank 시
 * Fail Fast를 실측한다.
 *
 * <p>startup 시 build.gradle.kts가 주입한 더미 자격으로 이미 SUPER_ADMIN이 생성돼 있으므로, 각 시나리오는 {@code @BeforeEach}
 * 에서 모든 SUPER_ADMIN 매핑·해당 user를 제거해 상태를 확정한다(컨테이너는 클래스 전용이라 타 테스트에 무영향). Runner의
 * {@code @Transactional}(프록시 경유 startup 경로)을 수동 호출에서도 재현하려 {@link TransactionTemplate}로 감싼다.
 */
@SpringBootTest
class SuperAdminBootstrapRunnerIntegrationTest {

    private static final long EXISTING_ADMIN_ID = 9701L;
    private static final String CREATE_EMAIL = "bootstrap-it-create@zslab.test";
    private static final String CREATE_PASSWORD = "bootstrap-it-create-pw-123456";
    private static final String EXISTING_EMAIL = "bootstrap-it-existing@zslab.test";
    private static final String EXISTING_PASSWORD = "bootstrap-it-existing-pw-123456";
    private static final String OTHER_EMAIL = "bootstrap-it-other@zslab.test";
    private static final String OTHER_PASSWORD = "bootstrap-it-other-pw-123456";

    static final MariaDBContainer<?> MARIADB;

    static {
        MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("(A) SUPER_ADMIN 부재 시: run() → user + user_role(SUPER_ADMIN) 생성·password는 BCrypt 인코딩 저장")
    void whenNoSuperAdmin_createsUserAndSuperAdminRole() {
        SuperAdminBootstrapRunner runner = newRunner(CREATE_EMAIL, CREATE_PASSWORD);

        tx.executeWithoutResult(status -> runner.run());

        assertThat(superAdminCount()).isEqualTo(1);
        assertThat(superAdminMappingCountByEmail(CREATE_EMAIL)).isEqualTo(1);

        String hash = passwordHashByEmail(CREATE_EMAIL);
        assertThat(hash).isNotNull();
        assertThat(hash).isNotEqualTo(CREATE_PASSWORD); // 평문 저장 금지·인코딩 확인
        assertThat(passwordEncoder.matches(CREATE_PASSWORD, hash)).isTrue();
    }

    @Test
    @DisplayName("(B) SUPER_ADMIN 존재 시: run() → 무작업(멱등)·기존 계정 email/password 불변(수정 금지 invariant)·중복 생성 없음")
    void whenSuperAdminExists_isIdempotentAndDoesNotModifyExisting() {
        String existingHash = passwordEncoder.encode(EXISTING_PASSWORD);
        seedSuperAdmin(EXISTING_ADMIN_ID, EXISTING_EMAIL, existingHash);

        // 기존과 다른 자격으로 재실행해도 덮어쓰지 않아야 한다(생성 전용).
        SuperAdminBootstrapRunner runner = newRunner(OTHER_EMAIL, OTHER_PASSWORD);
        tx.executeWithoutResult(status -> runner.run());

        assertThat(superAdminCount()).isEqualTo(1);                 // 중복 생성 없음
        assertThat(userExistsByEmail(OTHER_EMAIL)).isFalse();       // 새 자격으로 계정 미생성

        String hashAfter = passwordHashByEmail(EXISTING_EMAIL);
        assertThat(hashAfter).isEqualTo(existingHash);              // password 불변
        assertThat(passwordEncoder.matches(EXISTING_PASSWORD, hashAfter)).isTrue();
        assertThat(passwordEncoder.matches(OTHER_PASSWORD, hashAfter)).isFalse();
    }

    @Test
    @DisplayName("(C) SUPER_ADMIN 부재 + 필수 env blank: run() → IllegalStateException(Fail Fast)·계정 미생성")
    void whenNoSuperAdminAndBlankEnv_failsFast() {
        SuperAdminBootstrapRunner blankEmail = newRunner("", CREATE_PASSWORD);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> blankEmail.run()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(superAdminCount()).isZero();

        SuperAdminBootstrapRunner blankPassword = newRunner(CREATE_EMAIL, "");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> blankPassword.run()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(superAdminCount()).isZero();
    }

    // ---------- helpers (AuthControllerIntegrationTest 패턴·? positional 바인딩·SQL injection 없음) ----------

    private SuperAdminBootstrapRunner newRunner(String email, String password) {
        return new SuperAdminBootstrapRunner(
                userRepository, roleRepository, userRoleRepository, passwordEncoder, email, password);
    }

    // SUPER_ADMIN 매핑 보유 회원 수 = 부트스트랩된 슈퍼 관리자 수.
    private int superAdminCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role ur JOIN role r ON ur.role_id = r.id WHERE r.code = 'SUPER_ADMIN'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private int superAdminMappingCountByEmail(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role ur "
                        + "JOIN role r ON ur.role_id = r.id "
                        + "JOIN `user` u ON ur.user_id = u.id "
                        + "WHERE u.email = ? AND r.code = 'SUPER_ADMIN'",
                Integer.class, email);
        return count == null ? 0 : count;
    }

    private String passwordHashByEmail(String email) {
        return jdbc.queryForObject("SELECT password_hash FROM `user` WHERE email = ?", String.class, email);
    }

    private boolean userExistsByEmail(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    // role_id는 SUPER_ADMIN seed(V11)를 code로 조회해 seed-id 하드코딩을 피한다.
    private void seedSuperAdmin(long userId, String email, String passwordHash) {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        userId, pid("usr_", "T38SA"), email, passwordHash);
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) "
                                + "SELECT ?, id, NOW(6) FROM role WHERE code = 'SUPER_ADMIN'",
                        userId);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // startup 더미 부트스트랩 + 시나리오가 만든 모든 SUPER_ADMIN을 제거해 상태 확정(컨테이너는 클래스 전용).
                List<Long> superAdminUserIds = jdbc.queryForList(
                        "SELECT ur.user_id FROM user_role ur JOIN role r ON ur.role_id = r.id "
                                + "WHERE r.code = 'SUPER_ADMIN'",
                        Long.class);
                jdbc.update("DELETE FROM user_role WHERE role_id = (SELECT id FROM role WHERE code = 'SUPER_ADMIN')");
                for (Long id : superAdminUserIds) {
                    jdbc.update("DELETE FROM `user` WHERE id = ?", id);
                }
                jdbc.update("DELETE FROM `user` WHERE email IN (?, ?, ?)", CREATE_EMAIL, EXISTING_EMAIL, OTHER_EMAIL);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

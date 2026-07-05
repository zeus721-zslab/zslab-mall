package com.zslab.mall.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 회원가입 endpoint E2E 통합 테스트(Track 34·실 MariaDB). HTTP → UserController → UserService → DB 흐름을 실 커밋·HTTP
 * 경유로 검증한다. 정상 가입(201)은 user·user_role(BUYER) 저장을, 중복은 409, 형식 위반은 400을 확인한다.
 *
 * <p>BUYER Role은 V11 Flyway seed로 존재한다(findByCode 재사용). 시드·정리는 {@link TransactionTemplate} +
 * {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)이며, 테스트 email은 'signup-it-%' prefix로 격리·정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignupIntegrationTest {

    private static final String EMAIL_PREFIX = "signup-it-";
    private static final String NEW_EMAIL = EMAIL_PREFIX + "new@zslab.test";
    private static final String DUP_EMAIL = EMAIL_PREFIX + "dup@zslab.test";
    private static final String BAD_EMAIL = EMAIL_PREFIX + "bad@zslab.test";
    private static final String VALID_PASSWORD = "password123";

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
    private MockMvc mockMvc;
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
    @DisplayName("(1) 유효 가입 → 201·userPublicId 반환·user·BUYER user_role 저장")
    void validSignup_returns201_andPersistsUserRole() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(NEW_EMAIL, "홍길동", "010-1234-5678", VALID_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userPublicId").exists());

        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE email = ?", Integer.class, NEW_EMAIL);
        assertThat(userCount).isEqualTo(1);

        // BUYER role(V11 seed)이 실제 매핑됐는지 확인 — role 배선의 핵심 검증
        Integer buyerRoleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role ur "
                        + "JOIN `user` u ON ur.user_id = u.id "
                        + "JOIN role r ON ur.role_id = r.id "
                        + "WHERE u.email = ? AND r.code = 'BUYER'", Integer.class, NEW_EMAIL);
        assertThat(buyerRoleCount).isEqualTo(1);

        // BuyerProfile 배선(BL-8 회귀 가드): 가입 시 초기 프로필이 SILVER·AUTO로 생성됐는지 확인
        Integer buyerProfileCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM buyer_profile bp "
                        + "JOIN `user` u ON bp.user_id = u.id "
                        + "WHERE u.email = ?", Integer.class, NEW_EMAIL);
        assertThat(buyerProfileCount).isEqualTo(1);

        String gradeCode = jdbc.queryForObject(
                "SELECT bg.code FROM buyer_profile bp "
                        + "JOIN `user` u ON bp.user_id = u.id "
                        + "JOIN buyer_grade bg ON bp.grade_id = bg.id "
                        + "WHERE u.email = ?", String.class, NEW_EMAIL);
        assertThat(gradeCode).isEqualTo("SILVER");

        String gradeSource = jdbc.queryForObject(
                "SELECT bp.grade_source FROM buyer_profile bp "
                        + "JOIN `user` u ON bp.user_id = u.id "
                        + "WHERE u.email = ?", String.class, NEW_EMAIL);
        assertThat(gradeSource).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("(2) email 중복 → 409·code EMAIL_ALREADY_EXISTS")
    void duplicateEmail_returns409() throws Exception {
        seedExistingUser(DUP_EMAIL);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(DUP_EMAIL, "김철수", "010-9999-8888", VALID_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("(3) password 8자 미만 → 400·code VALIDATION_FAILED")
    void shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(BAD_EMAIL, "이영희", "010-7777-6666", "short12")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("(4) email 254자 초과 → 400·code VALIDATION_FAILED (기존 DB 제약 500 누수 교정)")
    void tooLongEmail_returns400() throws Exception {
        // 10 + 250 + 11 = 271자 > User.email @Column(length=254) → @Size(max=254) 위반(@Pattern은 통과)
        String tooLongEmail = EMAIL_PREFIX + "a".repeat(250) + "@zslab.test";
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(tooLongEmail, "홍길동", "010-1234-5678", VALID_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- seed·helpers (AuthControllerIntegrationTest 패턴·? positional 바인딩·SQL injection 없음) ----------

    private void seedExistingUser(String email) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (public_id, email, password_hash, created_at, updated_at) "
                                + "VALUES (?, ?, ?, NOW(6), NOW(6))",
                        pid("usr_", "EXIST1"), email, "$2a$10$dummydummydummydummydummydummydummydummydummydummydu");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // LIKE 패턴은 ? 바인딩 값으로 전달(SQL 구조 문자열에 값 concat 금지·injection 없음)
                jdbc.update("DELETE FROM user_role WHERE user_id IN "
                        + "(SELECT id FROM `user` WHERE email LIKE ?)", EMAIL_PREFIX + "%");
                jdbc.update("DELETE FROM buyer_profile WHERE user_id IN "
                        + "(SELECT id FROM `user` WHERE email LIKE ?)", EMAIL_PREFIX + "%");
                jdbc.update("DELETE FROM `user` WHERE email LIKE ?", EMAIL_PREFIX + "%");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String signupBody(String email, String name, String phone, String password) {
        return "{\"email\":\"" + email + "\",\"name\":\"" + name + "\",\"phone\":\"" + phone
                + "\",\"password\":\"" + password + "\"}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

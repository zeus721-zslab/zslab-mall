package com.zslab.mall.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import java.sql.Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 본인 회원 탈퇴 endpoint E2E 통합 테스트(Track 60 BL-5·실 MariaDB). HTTP → UserController → UserService → DB 흐름을
 * 실 커밋·HTTP 경유로 검증한다. 커버: 성공 204·재탈퇴 멱등(최초 시각 유지)·탈퇴 후 재로그인 차단 실효(AuthService 가드 자동 발동)·
 * 미인증 401.
 *
 * <p>재로그인 차단 케이스는 우연일치를 배제하기 위해 탈퇴 <b>전</b> 로그인 200(계정이 완전히 로그인 가능함)을 먼저 실증한 뒤
 * 탈퇴 <b>후</b> 동일 credential 로그인 401을 확인한다. 그래서 seed는 실 {@link PasswordEncoder}(BCrypt) password_hash +
 * BUYER user_role(V11 seed를 code로 조회) 매핑을 갖춘 로그인 가능 유저 1행이다. DB 커밋을 JdbcTemplate 직접 조회로
 * 검증하므로 클래스에 @Transactional을 두지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WithdrawControllerIntegrationTest {

    private static final String URL = "/api/v1/users/me/withdraw";
    private static final String LOGIN_URL = "/api/v1/auth/login";

    private static final long WITHDRAW_USER_ID = 9680L;
    private static final String EMAIL = "withdraw-it@zslab.test";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String FAILURE_MESSAGE = "Invalid email or password.";

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
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("(1) 인증 후 탈퇴 → 204 + DB withdrawn_at 마킹")
    void withdraw_returns204_marksWithdrawnAt() throws Exception {
        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isNoContent());

        Timestamp withdrawnAt = jdbc.queryForObject(
                "SELECT withdrawn_at FROM `user` WHERE id=?", Timestamp.class, WITHDRAW_USER_ID);
        assertThat(withdrawnAt).isNotNull();
    }

    @Test
    @DisplayName("(2) 재탈퇴 멱등 → 204 + withdrawn_at 최초 시각 유지(덮어쓰기 없음)")
    void withdraw_idempotent_keepsFirstTimestamp() throws Exception {
        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isNoContent());
        Timestamp first = jdbc.queryForObject(
                "SELECT withdrawn_at FROM `user` WHERE id=?", Timestamp.class, WITHDRAW_USER_ID);

        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isNoContent());
        Timestamp second = jdbc.queryForObject(
                "SELECT withdrawn_at FROM `user` WHERE id=?", Timestamp.class, WITHDRAW_USER_ID);

        // 재탈퇴는 no-op — 최초 탈퇴 시각을 유지한다(가드 제거 회귀 시 second != first로 실패).
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("(3) 탈퇴 후 재로그인 차단 실효 → 탈퇴 전 200·탈퇴 후 401(ACCOUNT_DISABLED 통합 401)")
    void withdraw_thenLogin_returns401() throws Exception {
        // 탈퇴 전: 동일 credential 로그인 성공(계정이 완전히 로그인 가능함을 실증·우연일치 배제).
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());

        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isNoContent());

        // 탈퇴 후: withdrawn_at != null 가드 발동 → 사유 무관 401·동일 메시지(계정 열거 방지).
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD, "BUYER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(FAILURE_MESSAGE));
    }

    @Test
    @DisplayName("(4) 미인증(토큰 없음) → 401 UNAUTHENTICATED")
    void withdraw_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(URL).headers(new HttpHeaders()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- seed·helpers (? positional 바인딩·정적 SQL·SQL injection 없음) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        WITHDRAW_USER_ID, pid("usr_", "WDRUSR"), EMAIL, passwordEncoder.encode(PASSWORD));
                // 로그인 fail-closed RBAC(BUYER) 통과에 user_role 매핑 실존 필요. role_id는 V11 seed를 code로 조회(하드코딩 금지).
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) "
                                + "SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                        WITHDRAW_USER_ID, "BUYER");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // FK RESTRICT 회귀 방지: 자식(user_role)을 user보다 먼저 삭제.
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", WITHDRAW_USER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", WITHDRAW_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String loginBody(String email, String password, String role) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

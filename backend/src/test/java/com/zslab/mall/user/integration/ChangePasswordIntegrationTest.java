package com.zslab.mall.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.TokenProvider;
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
 * 비밀번호 변경 endpoint E2E 통합 테스트(Track 34·실 MariaDB). HTTP → UserController → UserService → DB 흐름을 검증한다.
 * 정상(204)은 DB hash가 새 비번으로 교체됨을, 미인증은 401(SecurityConfig fail-closed), 현재 비번 불일치는 400을 확인한다.
 *
 * <p>인증은 실 {@link TokenProvider}로 발급한 JWT(role 무관·anyRequest authenticated)를 Bearer로 전달한다.
 * 시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·password_hash는 실 BCrypt 해싱이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChangePasswordIntegrationTest {

    private static final long USER_ID = 8500L;
    private static final String EMAIL = "changepw-it@zslab.test";
    private static final String CURRENT_PASSWORD = "current-password";
    private static final String NEW_PASSWORD = "new-password-123";

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
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TokenProvider tokenProvider;

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
    @DisplayName("(1) 유효 토큰+현재 비번 일치 → 204·DB hash가 새 비번으로 교체")
    void validChange_returns204_andRehashes() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        String newHash = jdbc.queryForObject(
                "SELECT password_hash FROM `user` WHERE id = ?", String.class, USER_ID);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, newHash)).isTrue();
    }

    @Test
    @DisplayName("(2) 인증 없이 호출 → 401 (SecurityConfig fail-closed)")
    void noAuth_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("(3) 현재 비번 불일치 → 400")
    void wrongCurrentPassword_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody("wrong-password", NEW_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    // ---------- seed·helpers (AuthControllerIntegrationTest 패턴·? positional 바인딩·SQL injection 없음) ----------

    private String bearer() {
        return "Bearer " + tokenProvider.issue(USER_ID, ActorRole.BUYER);
    }

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "CHGPW1"), EMAIL, passwordEncoder.encode(CURRENT_PASSWORD));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String changeBody(String currentPassword, String newPassword) {
        return "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

package com.zslab.mall.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.TokenPayload;
import com.zslab.mall.common.security.TokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 로그인 endpoint E2E 통합 테스트(Track 33·실 MariaDB). HTTP → AuthController → AuthService → DB 흐름을 실 커밋·HTTP
 * 경유로 검증한다. 성공 시 토큰을 실 {@link TokenProvider}로 verify해 actorId·role 일치를 확인하고, 실패(비번·미존재·비활성)는
 * 사유 무관 401·"Invalid email or password."로 통일됨(계정 열거 방지)을 확인한다.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally), password_hash는 실
 * {@link PasswordEncoder}(BCrypt) 해싱 값을 주입한다. 로그인 경로는 SecurityConfig permitAll이라 인증 헤더 없이 호출한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    private static final long USER_ID = 9440L;
    private static final long NULL_PW_USER_ID = 9441L;
    private static final String EMAIL = "login-test@zslab.test";
    private static final String NULL_PW_EMAIL = "nullpw@zslab.test";
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
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TokenProvider tokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("(1) 유효 credential+role=BUYER → 200·토큰 반환·verify 시 actorId·role 일치")
    void validCredentials_returns200_andVerifiableToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        TokenPayload payload = tokenProvider.verify(token);
        assertThat(payload.actorId()).isEqualTo(USER_ID);
        assertThat(payload.role()).isEqualTo(ActorRole.BUYER);
    }

    @Test
    @DisplayName("(2) 잘못된 비번 → 401·Invalid email or password.")
    void wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, "wrong-password", "BUYER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(FAILURE_MESSAGE));
    }

    @Test
    @DisplayName("(3) 미존재 email → 401 동일 메시지(enumeration 방지)")
    void unknownEmail_returns401_sameMessage() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@zslab.test", PASSWORD, "BUYER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(FAILURE_MESSAGE));
    }

    @Test
    @DisplayName("(4) passwordHash null 유저 → 401 동일 메시지")
    void nullPasswordHashUser_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(NULL_PW_EMAIL, PASSWORD, "BUYER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(FAILURE_MESSAGE));
    }

    @Test
    @DisplayName("(5) role 자격 검증 통과(Stub): role=SELLER 유효 로그인 → 200·토큰 role=SELLER")
    void roleAuthorizationStub_passes_returns200() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD, "SELLER")))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        assertThat(tokenProvider.verify(token).role()).isEqualTo(ActorRole.SELLER);
    }

    // ---------- seed·helpers (SellerDeliveryIntegrationTest 패턴·? positional 바인딩·SQL injection 없음) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "AUTH1"), EMAIL, passwordEncoder.encode(PASSWORD));
                jdbc.update("INSERT INTO `user` (id, public_id, email, created_at, updated_at) "
                                + "VALUES (?, ?, ?, NOW(6), NOW(6))",
                        NULL_PW_USER_ID, pid("usr_", "AUTH2"), NULL_PW_EMAIL);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?)", USER_ID, NULL_PW_USER_ID);
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

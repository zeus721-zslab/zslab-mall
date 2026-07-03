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
    private static final long SELLER_USER_ID = 9442L;
    private static final long SELLER_ID = 9443L;
    private static final String EMAIL = "login-test@zslab.test";
    private static final String NULL_PW_EMAIL = "nullpw@zslab.test";
    private static final String SELLER_EMAIL = "seller-test@zslab.test";
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
    @DisplayName("(5) 유효 credential+role=SELLER인데 seller_user 부재 → 401·fail-closed(role 위조 차단)")
    void sellerRoleWithoutSellerUser_returns401() throws Exception {
        // EMAIL 유저는 BUYER user_role만 보유·seller_user 행 없음 → SELLER 자격 없음(비번은 정상이나 role 판정에서 거부).
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD, "SELLER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(FAILURE_MESSAGE));
    }

    @Test
    @DisplayName("(6) seller_user 보유 유저+role=SELLER → 200·토큰 role=SELLER")
    void sellerRoleWithSellerUser_returns200() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(SELLER_EMAIL, PASSWORD, "SELLER")))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        TokenPayload payload = tokenProvider.verify(token);
        assertThat(payload.actorId()).isEqualTo(SELLER_USER_ID);
        assertThat(payload.role()).isEqualTo(ActorRole.SELLER);
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
                jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        SELLER_USER_ID, pid("usr_", "AUTH3"), SELLER_EMAIL, passwordEncoder.encode(PASSWORD));
                // (1) BUYER 성공: fail-closed RBAC 통과에 user_role 매핑 실존 필요. role_id는 V11 seed를 code로 조회(하드코딩 금지).
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) "
                                + "SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                        USER_ID, "BUYER");
                // (6) SELLER 성공: seller_user 행 존재만으로 SELLER 통과(role 종류 무관). seller 먼저 심고 SELLER_OWNER 매핑.
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "AUTH1"), "테스트셀러", "대표");
                jdbc.update("INSERT INTO seller_user (seller_id, user_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = ?",
                        SELLER_ID, SELLER_USER_ID, "SELLER_OWNER");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // FK RESTRICT 회귀 방지: 자식(user_role·seller_user)·seller를 user보다 먼저 삭제.
                jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?)",
                        USER_ID, NULL_PW_USER_ID, SELLER_USER_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id IN (?, ?, ?)",
                        USER_ID, NULL_PW_USER_ID, SELLER_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?)",
                        USER_ID, NULL_PW_USER_ID, SELLER_USER_ID);
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

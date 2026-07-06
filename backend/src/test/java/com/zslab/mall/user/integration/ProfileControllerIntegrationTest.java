package com.zslab.mall.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 회원 프로필 조회/수정 endpoint E2E 통합 테스트(Track 58 BL-3·실 MariaDB). HTTP → UserController → UserService → DB 흐름을
 * 검증한다. 커버: 조회 200·수정 200(name·phone 실 커밋·email 불변)·미인증 401·검증 실패 400. 소유권은 인증 주체 userId 스코프.
 *
 * <p>seed는 user 1행(email·name·phone)이다. 수정 커밋을 JdbcTemplate 직접 조회로 검증하므로 클래스에 @Transactional을 두지 않는다.
 * 인증은 {@link AuthHeaders}(실 TokenProvider JWT·role 무관 authenticated)로 주입한다.
 */
@AutoConfigureMockMvc
class ProfileControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/users/me";

    private static final long BUYER_USER_ID = 9670L;
    private static final String EMAIL = "profile-it@zslab.test";
    private static final String NAME = "홍길동";
    private static final String PHONE = "010-1234-5678";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

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
    @DisplayName("(1) 조회 → 200 + publicId·email·phone·name")
    void getMyProfile_returns200() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(pid("usr_", "PRFUSR")))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.phone").value(PHONE))
                .andExpect(jsonPath("$.name").isNotEmpty());
    }

    @Test
    @DisplayName("(2) 수정 → 200 + DB name·phone 교체·email 불변")
    void updateMyProfile_returns200_persists() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "김철수");
        body.put("phone", "010-0000-1111");

        mockMvc.perform(patch(URL)
                        .headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("010-0000-1111"));

        assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE id=?", String.class, BUYER_USER_ID))
                .isEqualTo("김철수");
        assertThat(jdbc.queryForObject("SELECT phone FROM `user` WHERE id=?", String.class, BUYER_USER_ID))
                .isEqualTo("010-0000-1111");
        assertThat(jdbc.queryForObject("SELECT email FROM `user` WHERE id=?", String.class, BUYER_USER_ID))
                .isEqualTo(EMAIL); // email은 본 경로에서 불변
    }

    @Test
    @DisplayName("(3) 미인증(토큰 없음) → 401 UNAUTHENTICATED")
    void getMyProfile_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(URL).headers(new HttpHeaders()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("(4) 수정 name 공백 → 400 VALIDATION_FAILED·DB 불변")
    void updateMyProfile_blankName_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", " ");
        body.put("phone", "010-0000-1111");

        mockMvc.perform(patch(URL)
                        .headers(authHeaders.buyer(BUYER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.queryForObject("SELECT phone FROM `user` WHERE id=?", String.class, BUYER_USER_ID))
                .isEqualTo(PHONE); // 검증 실패라 미변경
    }

    // ---------- seed·helpers (? positional 바인딩·정적 SQL·SQL injection 없음) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                        BUYER_USER_ID, pid("usr_", "PRFUSR"), EMAIL, NAME, PHONE);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

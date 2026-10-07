package com.zslab.mall.auth.integration;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비밀번호 재설정 비활성(D-269·기본 EMAIL_SENDER=mock = 운영 기본값). 사용 가능 여부 false · 요청은 같은 202지만 토큰·발송 기록을 만들지 않는다.
 * 비활성 요청은 실행기에 넘기지 않고 즉시 반환하므로 대기 없이 판정한다. 시드 SQL은 ? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class PasswordResetDisabledIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 9876L;
    private static final String BUYER_EMAIL = "reset-disabled@zslab.test";
    /** 활성 시 발급(조회·토큰 저장·Mock 발송)이 끝나는 데 충분한 관찰 구간. 활성 IT에서 발급→메일 도착은 1초 미만이다. */
    private static final Duration NOTHING_ISSUED_WINDOW = Duration.ofSeconds(3);

    @Autowired
    private MockMvc mockMvc;
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
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                    BUYER_ID, "usr_T269DIS0000000000000000000", BUYER_EMAIL, passwordEncoder.encode("old-password-1"));
            jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'BUYER'", BUYER_ID);
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("mock(기본) → 사용 가능 여부 false")
    void availability_mock_false() throws Exception {
        mockMvc.perform(get("/api/v1/auth/password-reset/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("mock(기본) → 구매자 이메일 요청도 202 · 토큰·발송 기록 없음")
    void request_mock_acceptedButNothingIssued() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + BUYER_EMAIL + "\"}"))
                .andExpect(status().isAccepted());

        // 발급은 비동기 실행기에서 일어나므로 즉시 0건 단언은 비활성 가드가 없어도 통과할 수 있다(false-green) — 발급이 끝나기에 충분한 시간 동안
        // 계속 0건인지 본다(가드 제거 시 이 구간 안에 토큰·발송 기록이 생겨 실패한다).
        await().during(NOTHING_ISSUED_WINDOW).atMost(NOTHING_ISSUED_WINDOW.plusSeconds(1)).until(() ->
                jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", Integer.class, BUYER_ID) == 0
                        && jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE recipient_user_id = ?", Integer.class, BUYER_ID) == 0);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM password_reset_token WHERE user_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", BUYER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

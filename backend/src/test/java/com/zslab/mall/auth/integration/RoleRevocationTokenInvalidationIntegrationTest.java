package com.zslab.mall.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 역할 회수 시 기존 토큰 무효화 통합 테스트(최종 점검 K2·실 MariaDB). 회수 성공이 대상의 credentials_changed_at을 갱신해, 회수 전에 발급된
 * 관리자 토큰이 다음 요청부터 401이 되는지 HTTP 경유로 검증한다. 회수 요청자(SUPER_ADMIN) 세션은 영향받지 않는다.
 *
 * <p>iat가 초 단위라 "발급 → 즉시 회수"가 같은 초에 걸리면 판정이 흔들리므로 5초 전 iat로 서명한 백데이트 토큰을 쓴다(AdminMemberIntegrationTest 선례).
 */
@AutoConfigureMockMvc
class RoleRevocationTokenInvalidationIntegrationTest extends AbstractIntegrationTest {

    private static final long SUPER_CALLER = 9871L;
    private static final long OPERATOR = 9872L;  // ADMIN_OPERATOR만 · 회수 대상
    private static final long BUYER = 9873L;     // 관리자 쓰기 대상(회원 정보 수정)
    private static final String OPERATOR_PID = pid("usr_", "K2ROPR");
    private static final String BUYER_PID = pid("usr_", "K2RBUY");
    private static final String REASON_BODY = "{\"reason\":\"K2 회수 토큰 무효화 검증\"}";
    private static final String UPDATE_BODY = "{\"name\":\"이름변경\",\"phone\":\"010-2222-3333\"}";
    private static final int BACKDATE_MILLIS = 5_000;
    private static final long TOKEN_TTL_MILLIS = 3_600_000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Value("${jwt.secret}")
    private String jwtSecret;

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
    @DisplayName("ADMIN_OPERATOR 회수 전 토큰: 회수 전 관리자 쓰기 204 → 회수 204(credentials_changed_at 기록) → 같은 토큰 401 · 요청자 세션 204 유지")
    void revokedOperatorToken_isRejectedOnNextRequest() throws Exception {
        String operatorToken = backdatedAdminToken(OPERATOR);
        updateMemberWith(operatorToken).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/admin/users/" + OPERATOR_PID + "/roles/ADMIN_OPERATOR").with(authHeaders.admin(SUPER_CALLER))
                        .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                .andExpect(status().isNoContent());

        updateMemberWith(operatorToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(jdbc.queryForObject("SELECT credentials_changed_at IS NOT NULL FROM `user` WHERE id = ?", Boolean.class, OPERATOR))
                .isTrue();
        mockMvc.perform(patch("/api/v1/admin/members/" + BUYER_PID).with(authHeaders.admin(SUPER_CALLER))
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isNoContent());
    }

    // ---------- helpers ----------

    private ResultActions updateMemberWith(String adminToken) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/members/" + BUYER_PID)
                .cookie(new Cookie(AuthCookies.ADMIN_COOKIE, adminToken))
                .with(authHeaders.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY));
    }

    /** iat를 {@value #BACKDATE_MILLIS}ms 앞당긴 관리자 토큰 — "회수 시각 이전 발급"을 초 단위 경계와 무관하게 재현한다. */
    private String backdatedAdminToken(long userId) {
        long issued = System.currentTimeMillis() - BACKDATE_MILLIS;
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", ActorRole.ADMIN.name())
                .issuedAt(new Date(issued))
                .expiration(new Date(issued + TOKEN_TTL_MILLIS))
                .signWith(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256)
                .compact();
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    // 모든 SQL은 ? 바인딩·SQL injection 없음.
    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedUser(SUPER_CALLER, pid("usr_", "K2RSUP"), "k2r-super@zslab.test");
                seedRole(SUPER_CALLER, "SUPER_ADMIN");
                seedUser(OPERATOR, OPERATOR_PID, "k2r-operator@zslab.test");
                seedRole(OPERATOR, "ADMIN_OPERATOR");
                seedUser(BUYER, BUYER_PID, "k2r-buyer@zslab.test");
                seedRole(BUYER, "BUYER");
                jdbc.update("INSERT INTO buyer_profile (user_id, grade_id, grade_source, created_at, updated_at) "
                        + "VALUES (?, (SELECT id FROM buyer_grade WHERE code = 'SILVER'), 'AUTO', NOW(6), NOW(6))", BUYER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedUser(long id, String publicId, String email) {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, password_hash, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'K2회수', '010-1111-2222', 'unused-hash', NOW(6), NOW(6))", id, publicId, email);
    }

    private void seedRole(long userId, String roleCode) {
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?", userId, roleCode);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (long id : List.of(SUPER_CALLER, OPERATOR, BUYER)) {
                    jdbc.update("DELETE FROM audit_log WHERE target_type = 'USER' AND target_id = ?", id);
                    jdbc.update("DELETE FROM buyer_profile WHERE user_id = ?", id);
                    jdbc.update("DELETE FROM user_role WHERE user_id = ?", id);
                    jdbc.update("DELETE FROM `user` WHERE id = ?", id);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

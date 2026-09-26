package com.zslab.mall.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 마지막 슈퍼 관리자 보호 통합 테스트(D-230·실 MariaDB). 본인 탈퇴·관리자 탈퇴·SUPER_ADMIN 역할 회수 3경로가 같은 가드
 * (LastSuperAdminGuard)로 "활성 SUPER_ADMIN 0명"을 막는지 HTTP 경유로 검증한다. 집계는 탈퇴하지 않은 보유자만 센다.
 *
 * <p>전역 부트스트랩 SUPER_ADMIN(build.gradle.kts 테스트 env)이 활성 인원에 들어가므로 정리에서 제거한다
 * (AdminUserRoleControllerIntegrationTest 정리 패턴 정합). 동시 요청은 LastSuperAdminRaceIntegrationTest가 맡는다.
 */
@AutoConfigureMockMvc
class LastSuperAdminProtectionIntegrationTest extends AbstractIntegrationTest {

    private static final String SELF_WITHDRAW_URL = "/api/v1/users/me/withdraw";
    private static final String ADMIN_MEMBERS_URL = "/api/v1/admin/members/";
    private static final String LAST_SUPER_ADMIN_MESSAGE = "마지막 슈퍼 관리자는 탈퇴하거나 권한을 해제할 수 없습니다.";
    private static final String REASON_BODY = "{\"reason\":\"D-230 탈퇴 슈퍼 관리자 역할 정리\"}";

    private static final long SUPER_A = 9731L;   // SUPER_ADMIN + BUYER(관리자 탈퇴 경로 대상이 되려면 BUYER 겸직 필요)
    private static final long SUPER_B = 9732L;   // SUPER_ADMIN(2명 허용·탈퇴 혼재 시나리오)
    private static final long OPERATOR = 9733L;  // ADMIN_OPERATOR 호출자(관리자 탈퇴 경로)
    private static final String PID_A = pid("usr_", "D230SAA");
    private static final String PID_B = pid("usr_", "D230SAB");
    private static final String PID_OPERATOR = pid("usr_", "D230OPR");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
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
    @DisplayName("본인 탈퇴: 유일한 활성 슈퍼 관리자 → 409 LAST_SUPER_ADMIN·문구·withdrawn_at 미기록")
    void selfWithdraw_lastSuperAdmin_returns409() throws Exception {
        seed(() -> seedSuperAdmin(SUPER_A, PID_A, false, true));

        mockMvc.perform(post(SELF_WITHDRAW_URL).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"))
                .andExpect(jsonPath("$.detail").value(LAST_SUPER_ADMIN_MESSAGE));

        assertThat(isWithdrawn(SUPER_A)).isFalse();
    }

    @Test
    @DisplayName("관리자 회원 탈퇴: BUYER를 겸한 유일한 활성 슈퍼 관리자 → 409 LAST_SUPER_ADMIN·withdrawn_at 미기록")
    void adminWithdraw_lastSuperAdmin_returns409() throws Exception {
        seed(() -> {
            seedSuperAdmin(SUPER_A, PID_A, false, true);
            seedUser(OPERATOR, PID_OPERATOR, false);
            seedUserRole(OPERATOR, "ADMIN_OPERATOR");
        });

        mockMvc.perform(post(ADMIN_MEMBERS_URL + PID_A + "/withdraw").headers(authHeaders.admin(OPERATOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"));

        assertThat(isWithdrawn(SUPER_A)).isFalse();
    }

    @Test
    @DisplayName("활성 슈퍼 관리자 2명: 본인 탈퇴 → 204·다른 1명 유지")
    void selfWithdraw_twoSuperAdmins_allowed() throws Exception {
        seed(() -> {
            seedSuperAdmin(SUPER_A, PID_A, false, true);
            seedSuperAdmin(SUPER_B, PID_B, false, false);
        });

        mockMvc.perform(post(SELF_WITHDRAW_URL).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isNoContent());

        assertThat(isWithdrawn(SUPER_A)).isTrue();
        assertThat(activeSuperAdminCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴한 슈퍼 관리자 혼재(user_role 잔존·보유 2 / 활성 1): 활성 1명의 본인 탈퇴 → 409(탈퇴자는 인원에서 제외)")
    void selfWithdraw_withWithdrawnSuperAdminMixed_returns409() throws Exception {
        seed(() -> {
            seedSuperAdmin(SUPER_A, PID_A, false, true);
            seedSuperAdmin(SUPER_B, PID_B, true, false); // 탈퇴했지만 SUPER_ADMIN 매핑은 남아 있음(탈퇴는 user_role을 지우지 않는다)
        });

        mockMvc.perform(post(SELF_WITHDRAW_URL).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"));

        assertThat(isWithdrawn(SUPER_A)).isFalse();
    }

    @Test
    @DisplayName("역할 회수: 탈퇴한 슈퍼 관리자의 SUPER_ADMIN 정리는 활성 인원을 줄이지 않아 허용(활성 1명이어도 204)")
    void revoke_withdrawnSuperAdminRole_allowed() throws Exception {
        seed(() -> {
            seedSuperAdmin(SUPER_A, PID_A, false, false);
            seedSuperAdmin(SUPER_B, PID_B, true, false);
        });

        mockMvc.perform(delete("/api/v1/admin/users/" + PID_B + "/roles/SUPER_ADMIN").headers(authHeaders.admin(SUPER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                .andExpect(status().isNoContent());

        assertThat(activeSuperAdminCount()).isEqualTo(1);
    }

    // ---------- helpers (모든 SQL은 ? 바인딩·SQL injection 없음) ----------

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    private void seed(Runnable seedingWork) {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedingWork.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSuperAdmin(long id, String publicId, boolean withdrawn, boolean buyer) {
        seedUser(id, publicId, withdrawn);
        seedUserRole(id, "SUPER_ADMIN");
        if (buyer) {
            seedUserRole(id, "BUYER");
            jdbc.update("INSERT INTO buyer_profile (user_id, grade_id, grade_source, created_at, updated_at) "
                    + "VALUES (?, (SELECT id FROM buyer_grade WHERE code = 'SILVER'), 'AUTO', NOW(6), NOW(6))", id);
        }
    }

    private void seedUser(long id, String publicId, boolean withdrawn) {
        jdbc.update("INSERT INTO `user` (id, public_id, withdrawn_at, created_at, updated_at) "
                        + "VALUES (?, ?, IF(?, NOW(6), NULL), NOW(6), NOW(6))",
                id, publicId, withdrawn);
    }

    // role_id는 V11 seed를 code로 조회해 seed-id 하드코딩을 피한다.
    private void seedUserRole(long userId, String roleCode) {
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                userId, roleCode);
    }

    private boolean isWithdrawn(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT withdrawn_at IS NOT NULL FROM `user` WHERE id = ?", Boolean.class, userId));
    }

    private int activeSuperAdminCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role ur JOIN role r ON ur.role_id = r.id JOIN `user` u ON u.id = ur.user_id "
                        + "WHERE r.code = 'SUPER_ADMIN' AND u.withdrawn_at IS NULL AND u.deleted_at IS NULL",
                Integer.class);
        return count == null ? 0 : count;
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // 전역 부트스트랩 SUPER_ADMIN도 활성 인원에 들어가므로 제거해 인원을 확정한다(AdminUserRoleControllerIntegrationTest 정합).
                List<Long> superAdminUserIds = jdbc.queryForList(
                        "SELECT ur.user_id FROM user_role ur JOIN role r ON ur.role_id = r.id WHERE r.code = 'SUPER_ADMIN'",
                        Long.class);
                jdbc.update("DELETE FROM user_role WHERE role_id = (SELECT id FROM role WHERE code = 'SUPER_ADMIN')");
                for (Long id : superAdminUserIds) {
                    jdbc.update("DELETE FROM `user` WHERE id = ?", id);
                }
                for (long id : List.of(SUPER_A, SUPER_B, OPERATOR)) {
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

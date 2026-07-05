package com.zslab.mall.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 권한 회수 endpoint E2E 통합 테스트(Track 53·AUTH-4/5/6·실 MariaDB). HTTP DELETE → {@code AdminUserRoleController} →
 * {@code RoleRevocationService} → user_role/audit_log DB 흐름을 실 커밋·HTTP 경유로 실측한다({@link
 * AdminOperatorControllerIntegrationTest}·{@link com.zslab.mall.audit.AuditWiringIntegrationTest} 패턴 정합).
 *
 * <p><b>2단 인가</b>: SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}는 코어스 필터 게이트(①), "SUPER_ADMIN만
 * 회수"는 서비스가 user_role 실조회로 강제(②·fail-closed).
 *
 * <p><b>self/last 방어(AUTH-5·6)</b>: self 검사는 회수 대상 roleCode가 SUPER_ADMIN일 때만 적용하므로(③), SUPER_ADMIN이 자기
 * ADMIN_OPERATOR를 회수하는 경우는 차단하지 않는다(⑧이 방증). 마지막 SUPER_ADMIN 회수는 Role 행 FOR UPDATE 하 count&lt;=1
 * 판정으로 409 차단한다(④). self 검사가 last 판정보다 선행하므로, count&lt;=1인 sole SUPER_ADMIN이 타 대상의 SUPER_ADMIN을
 * 회수 시도하면 409가 먼저 발화한다(④는 이 경로).
 *
 * <p><b>감사(AUTH-4)</b>: 회수 성공 시 같은 트랜잭션에서 audit_log 1건(DELETE/USER/target_id·diff role 키 삭제)을 남긴다(⑤).
 *
 * <p><b>트랜잭션</b>: 커밋 결과를 JdbcTemplate로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다. 시드/정리는
 * {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(try-finally)로 한다. audit_log는 actor_user_id 기준 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserRoleControllerIntegrationTest {

    private static final long SUPER_A = 9701L;          // caller·SUPER_ADMIN
    private static final long SUPER_B = 9702L;          // 대상·SUPER_ADMIN(성공·self 세팅용)
    private static final long OPERATOR_CALLER = 9703L;  // ADMIN_OPERATOR만(② 서비스 403)
    private static final long BUYER_CALLER = 9704L;     // BUYER 토큰(① 필터 403)
    private static final long PLAIN_TARGET = 9705L;     // 역할 미보유 대상(⑥ 404)

    private static final String PID_A = pid("usr_", "T53SAA");
    private static final String PID_B = pid("usr_", "T53SAB");
    private static final String PID_PLAIN = pid("usr_", "T53PLN");
    private static final String PID_MISSING = pid("usr_", "T53MISSING"); // 미시드(⑦ 404 통합 은닉)

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
    @DisplayName("① 비ADMIN: BUYER 토큰 → 403 FORBIDDEN(SecurityConfig hasRole 필터 게이트·미회수)")
    void revoke_buyerToken_returns403_atFilter() throws Exception {
        seed(() -> {
            seedUser(SUPER_A, PID_A);
            seedUserRole(SUPER_A, "SUPER_ADMIN");
        });

        mockMvc.perform(delete(url(PID_A, "SUPER_ADMIN")).headers(authHeaders.buyer(BUYER_CALLER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleMappingCount(SUPER_A, "SUPER_ADMIN")).isEqualTo(1);
    }

    @Test
    @DisplayName("② ADMIN이나 비SUPER_ADMIN: ADMIN_OPERATOR caller → 서비스 403 FORBIDDEN(fail-closed·미회수)")
    void revoke_adminButNotSuperAdmin_returns403_atService() throws Exception {
        seed(() -> {
            seedUser(OPERATOR_CALLER, pid("usr_", "T53OPC"));
            seedUserRole(OPERATOR_CALLER, "ADMIN_OPERATOR");
            seedUser(SUPER_B, PID_B);
            seedUserRole(SUPER_B, "SUPER_ADMIN");
        });

        mockMvc.perform(delete(url(PID_B, "SUPER_ADMIN")).headers(authHeaders.admin(OPERATOR_CALLER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleMappingCount(SUPER_B, "SUPER_ADMIN")).isEqualTo(1);
    }

    @Test
    @DisplayName("③ self-revoke: SUPER_ADMIN이 자기 SUPER_ADMIN 회수(2명 존재·last 미발화) → 403 FORBIDDEN·미회수")
    void revoke_selfSuperAdmin_returns403() throws Exception {
        seed(() -> {
            seedUser(SUPER_A, PID_A);
            seedUserRole(SUPER_A, "SUPER_ADMIN");
            seedUser(SUPER_B, PID_B);
            seedUserRole(SUPER_B, "SUPER_ADMIN"); // 2명 → last 방어 아닌 self 방어가 발화함을 격리
        });

        mockMvc.perform(delete(url(PID_A, "SUPER_ADMIN")).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(roleMappingCount(SUPER_A, "SUPER_ADMIN")).isEqualTo(1);
    }

    @Test
    @DisplayName("④ 마지막 SUPER_ADMIN: sole SUPER_ADMIN이 SUPER_ADMIN 회수 시도 → 409 LAST_SUPER_ADMIN·count 불변(1)")
    void revoke_lastSuperAdmin_returns409() throws Exception {
        seed(() -> {
            seedUser(SUPER_A, PID_A);
            seedUserRole(SUPER_A, "SUPER_ADMIN"); // 유일 SUPER_ADMIN(count=1)
            seedUser(SUPER_B, PID_B);
            seedUserRole(SUPER_B, "ADMIN_OPERATOR"); // 대상은 SUPER_ADMIN 미보유·count<=1 가드가 선발화
        });

        mockMvc.perform(delete(url(PID_B, "SUPER_ADMIN")).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"));

        assertThat(superAdminCount()).isEqualTo(1); // 회수 미수행(락아웃 방지)
    }

    @Test
    @DisplayName("⑤ 성공: SUPER_ADMIN 2명 중 1명 회수 → 204·매핑 삭제·audit DELETE/USER/target·diff role 키 삭제")
    void revoke_success_returns204_andRecordsAudit() throws Exception {
        seed(() -> {
            seedUser(SUPER_A, PID_A);
            seedUserRole(SUPER_A, "SUPER_ADMIN");
            seedUser(SUPER_B, PID_B);
            seedUserRole(SUPER_B, "SUPER_ADMIN");
        });

        mockMvc.perform(delete(url(PID_B, "SUPER_ADMIN")).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isNoContent());

        assertThat(roleMappingCount(SUPER_B, "SUPER_ADMIN")).isZero();
        assertThat(superAdminCount()).isEqualTo(1); // caller A만 잔존

        Map<String, Object> row = singleAuditRow("USER", SUPER_B, SUPER_A);
        assertThat(row.get("action")).isEqualTo("DELETE");
        assertThat(row.get("actor_role")).isEqualTo("ADMIN");
        // after는 빈 맵이라 role 키가 before만 남는다(회수=키 삭제). null after는 앱 ObjectMapper의 non-null 직렬화로 생략되나,
        // before-only 엔트리가 남아 diff가 비지 않으므로 감사 적재가 skip되지 않음을 방증한다(AUTH-4·row 존재 자체가 non-skip 증거).
        assertThat((String) row.get("diff_json"))
                .contains("role")
                .contains("\"before\":\"SUPER_ADMIN\"");
    }

    @Test
    @DisplayName("⑥ 미보유: 대상이 해당 역할 미보유(delete 0 row) → 404 ROLE_ASSIGNMENT_NOT_FOUND")
    void revoke_targetLacksRole_returns404() throws Exception {
        seed(() -> {
            seedUser(SUPER_A, PID_A);
            seedUserRole(SUPER_A, "SUPER_ADMIN");
            seedUser(PLAIN_TARGET, PID_PLAIN); // 역할 미보유
        });

        mockMvc.perform(delete(url(PID_PLAIN, "ADMIN_OPERATOR")).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_ASSIGNMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("⑦ 대상 public_id 미존재 → 404 ROLE_ASSIGNMENT_NOT_FOUND(User 미존재·미보유 통합 은닉)")
    void revoke_unknownTarget_returns404() throws Exception {
        seed(() -> {
            seedUser(SUPER_A, PID_A);
            seedUserRole(SUPER_A, "SUPER_ADMIN");
        });

        mockMvc.perform(delete(url(PID_MISSING, "ADMIN_OPERATOR")).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_ASSIGNMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("⑧ self-revoke 범위=SUPER_ADMIN 한정: SUPER_ADMIN이 자기 ADMIN_OPERATOR 회수 → 204(차단 안 됨)")
    void revoke_selfAdminOperator_notBlocked_returns204() throws Exception {
        seed(() -> {
            seedUser(SUPER_A, PID_A);
            seedUserRole(SUPER_A, "SUPER_ADMIN");
            seedUserRole(SUPER_A, "ADMIN_OPERATOR"); // caller가 자기 ADMIN_OPERATOR 보유
        });

        mockMvc.perform(delete(url(PID_A, "ADMIN_OPERATOR")).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isNoContent());

        assertThat(roleMappingCount(SUPER_A, "ADMIN_OPERATOR")).isZero();
        assertThat(roleMappingCount(SUPER_A, "SUPER_ADMIN")).isEqualTo(1); // SUPER_ADMIN은 유지
    }

    @Test
    @DisplayName("⑨ roleCode 오값: 미정의 enum 경로 → 400 MALFORMED_REQUEST(PathVariable 바인딩 실패)")
    void revoke_invalidRoleCode_returns400() throws Exception {
        // 필터 게이트(hasRole ADMIN)만 통과하면 PathVariable enum 변환 실패가 컨트롤러 진입 전 400으로 처리된다(시드 불요).
        mockMvc.perform(delete(url(PID_A, "BOGUS_ROLE")).headers(authHeaders.admin(SUPER_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---------- helpers (AdminOperatorControllerIntegrationTest 패턴·? positional 바인딩·SQL injection 없음) ----------

    private static String url(String userPublicId, String roleCode) {
        return "/api/v1/admin/users/" + userPublicId + "/roles/" + roleCode;
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

    private void seedUser(long id, String publicId) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                id, publicId);
    }

    // role_id는 V11 seed를 code로 조회해 seed-id 하드코딩을 피한다(AdminOperatorControllerIntegrationTest 패턴).
    private void seedUserRole(long userId, String roleCode) {
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) "
                        + "SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                userId, roleCode);
    }

    private int roleMappingCount(long userId, String roleCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role ur JOIN role r ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND r.code = ?",
                Integer.class, userId, roleCode);
        return count == null ? 0 : count;
    }

    // 테스트 시드 회원(9701~9705) 중 SUPER_ADMIN 보유자 수. 전역 seed(SUPER_ADMIN 부트스트랩)와 분리 카운트한다.
    private int superAdminCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role ur JOIN role r ON ur.role_id = r.id "
                        + "WHERE r.code = 'SUPER_ADMIN' AND ur.user_id IN (?, ?, ?, ?, ?)",
                Integer.class, SUPER_A, SUPER_B, OPERATOR_CALLER, BUYER_CALLER, PLAIN_TARGET);
        return count == null ? 0 : count;
    }

    private Map<String, Object> singleAuditRow(String targetType, long targetId, long actorUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, actor_user_id, actor_role, diff_json FROM audit_log "
                        + "WHERE target_type = ? AND target_id = ? AND actor_user_id = ?",
                targetType, targetId, actorUserId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE actor_user_id IN (?, ?, ?, ?, ?)",
                        SUPER_A, SUPER_B, OPERATOR_CALLER, BUYER_CALLER, PLAIN_TARGET);
                // startup 시 build.gradle.kts 더미 자격으로 부트스트랩된 전역 SUPER_ADMIN을 제거해 last-admin count를 확정한다
                // (RoleRevocationService.countByRole_Code는 전역 카운트·컨테이너는 클래스 전용이라 타 테스트 무영향·부트스트랩
                // 테스트 cleanup 패턴 정합). 시나리오가 시드한 SUPER_ADMIN 매핑도 함께 제거된다.
                List<Long> superAdminUserIds = jdbc.queryForList(
                        "SELECT ur.user_id FROM user_role ur JOIN role r ON ur.role_id = r.id "
                                + "WHERE r.code = 'SUPER_ADMIN'",
                        Long.class);
                jdbc.update("DELETE FROM user_role WHERE role_id = (SELECT id FROM role WHERE code = 'SUPER_ADMIN')");
                for (Long id : superAdminUserIds) {
                    jdbc.update("DELETE FROM `user` WHERE id = ?", id);
                }
                jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?, ?, ?)",
                        SUPER_A, SUPER_B, OPERATOR_CALLER, BUYER_CALLER, PLAIN_TARGET);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?, ?, ?)",
                        SUPER_A, SUPER_B, OPERATOR_CALLER, BUYER_CALLER, PLAIN_TARGET);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

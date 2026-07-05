package com.zslab.mall.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 운영 관리자(ADMIN_OPERATOR) 공급 endpoint E2E 통합 테스트(Track 38·실 MariaDB). HTTP → {@code AdminOperatorController} →
 * {@code AdminOperatorProvisioningService} → user_role DB 흐름을 실 커밋·HTTP 경유로 실측한다({@link
 * com.zslab.mall.seller.controller.AdminSellerControllerIntegrationTest} 패턴 정합).
 *
 * <p><b>2단 인가</b>: SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}는 코어스 필터 게이트다(②가 검증).
 * JWT는 SUPER_ADMIN authority를 담지 않으므로 "SUPER_ADMIN만 공급"이라는 세분 규칙은 서비스가 user_role 실조회로 강제한다
 * (③이 fail-closed 검증 — ADMIN 토큰이나 DB상 비-SUPER_ADMIN이면 403).
 *
 * <p><b>중복 가드 = DB 제약</b>: 이미 ADMIN_OPERATOR 보유 user 재부여 시 saveAndFlush가 uk_user_role(user_id, role_id)(V1)를
 * 위반해 409로 변환된다(⑤). ⑥은 409 후 부수 INSERT가 없어 매핑이 정확히 1건 유지됨을 검증한다(saveAndFlush catch 경로 관통).
 *
 * <p><b>트랜잭션</b>: 커밋 결과를 JdbcTemplate 직접 조회로 검증하므로 클래스에 {@code @Transactional}을 두지 않는다.
 * 시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)로 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminOperatorControllerIntegrationTest {

    private static final String URL = "/api/v1/admin/admin-operators";

    private static final long SUPER_ADMIN_CALLER = 9601L; // SUPER_ADMIN 매핑 보유(세분 인가 통과)
    private static final long OPERATOR_CALLER = 9602L;     // ADMIN_OPERATOR만 보유(SUPER_ADMIN 아님·③ 403)
    private static final long BUYER_CALLER = 9603L;        // BUYER 토큰(필터 게이트 거부·② 403)
    private static final long TARGET_USER = 9610L;         // 부여 대상(미보유 user)
    private static final long DUP_TARGET = 9611L;          // 이미 ADMIN_OPERATOR 보유(⑤⑥)
    private static final long MISSING_TARGET = 9699L;      // 미시드 user(④ 404)

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
    @DisplayName("① SUPER_ADMIN 성공: 대상에 ADMIN_OPERATOR 부여 → 201·userPublicId 반환·user_role(ADMIN_OPERATOR) 1건")
    void provision_bySuperAdmin_returns201_grantsAdminOperator() throws Exception {
        seed(() -> {
            seedUser(SUPER_ADMIN_CALLER, "T38SAC");
            seedUserRole(SUPER_ADMIN_CALLER, "SUPER_ADMIN");
            seedUser(TARGET_USER, "T38TGT");
        });

        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(SUPER_ADMIN_CALLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TARGET_USER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userPublicId").exists());

        assertThat(adminOperatorMappingCount(TARGET_USER)).isEqualTo(1);

        // 감사 배선(Track 55·D-139): 부여 성공 시 CREATE/USER 감사 1건·diff에 role:ADMIN_OPERATOR(회수 DELETE의 대칭·키-추가).
        Map<String, Object> audit = singleAuditRow("USER", TARGET_USER, SUPER_ADMIN_CALLER);
        assertThat(audit.get("action")).isEqualTo("CREATE");
        assertThat(audit.get("actor_role")).isEqualTo("ADMIN");
        assertThat((String) audit.get("diff_json")).contains("role").contains("ADMIN_OPERATOR");
    }

    @Test
    @DisplayName("② 비ADMIN: BUYER 토큰 → 403 FORBIDDEN·미부여(SecurityConfig hasRole 필터 게이트)")
    void provision_buyerToken_returns403_atFilter() throws Exception {
        // 필터 게이트가 컨트롤러 이전에 거부한다(caller/target DB 시드 불요).
        mockMvc.perform(post(URL)
                        .headers(authHeaders.buyer(BUYER_CALLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TARGET_USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(adminOperatorMappingCount(TARGET_USER)).isZero();
    }

    @Test
    @DisplayName("③ ADMIN이나 비SUPER_ADMIN: ADMIN_OPERATOR caller → 서비스 403 FORBIDDEN·미부여(fail-closed 세분 인가)")
    void provision_adminButNotSuperAdmin_returns403_atService() throws Exception {
        // ADMIN 토큰이라 필터 게이트(hasRole ADMIN)는 통과하나, DB상 SUPER_ADMIN이 아니므로 서비스가 거부한다.
        seed(() -> {
            seedUser(OPERATOR_CALLER, "T38OPC");
            seedUserRole(OPERATOR_CALLER, "ADMIN_OPERATOR");
            seedUser(TARGET_USER, "T38TGT");
        });

        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(OPERATOR_CALLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TARGET_USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(adminOperatorMappingCount(TARGET_USER)).isZero();
    }

    @Test
    @DisplayName("④ 대상 미존재: 미시드 userId → 404 USER_NOT_FOUND")
    void provision_unknownTarget_returns404() throws Exception {
        seed(() -> {
            seedUser(SUPER_ADMIN_CALLER, "T38SAC");
            seedUserRole(SUPER_ADMIN_CALLER, "SUPER_ADMIN");
        });

        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(SUPER_ADMIN_CALLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(MISSING_TARGET)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("⑤ 중복 부여(uk_user_role): 이미 ADMIN_OPERATOR 보유 user → 409 ADMIN_OPERATOR_ALREADY_EXISTS")
    void provision_alreadyOperator_returns409() throws Exception {
        seed(() -> {
            seedUser(SUPER_ADMIN_CALLER, "T38SAC");
            seedUserRole(SUPER_ADMIN_CALLER, "SUPER_ADMIN");
            seedUser(DUP_TARGET, "T38DUP");
            seedUserRole(DUP_TARGET, "ADMIN_OPERATOR");
        });

        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(SUPER_ADMIN_CALLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DUP_TARGET)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_OPERATOR_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("⑥ 중복 409 후 부수 INSERT 없음: ADMIN_OPERATOR 매핑이 정확히 1건 유지(saveAndFlush catch 경로 관통)")
    void provision_alreadyOperator_leavesNoResidue() throws Exception {
        seed(() -> {
            seedUser(SUPER_ADMIN_CALLER, "T38SAC");
            seedUserRole(SUPER_ADMIN_CALLER, "SUPER_ADMIN");
            seedUser(DUP_TARGET, "T38DUP");
            seedUserRole(DUP_TARGET, "ADMIN_OPERATOR");
        });

        mockMvc.perform(post(URL)
                        .headers(authHeaders.admin(SUPER_ADMIN_CALLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DUP_TARGET)))
                .andExpect(status().isConflict());

        // 실패한 saveAndFlush가 롤백돼 중복 행이 남지 않는다(정확히 기존 1건 유지).
        assertThat(adminOperatorMappingCount(DUP_TARGET)).isEqualTo(1);
    }

    // ---------- seed·helpers (AdminSellerControllerIntegrationTest 패턴·? positional 바인딩·SQL injection 없음) ----------

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

    private void seedUser(long id, String tag) {
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                id, pid("usr_", tag));
    }

    // role_id는 V11 seed를 code로 조회해 seed-id 하드코딩을 피한다(AuthControllerIntegrationTest 패턴).
    private void seedUserRole(long userId, String roleCode) {
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) "
                        + "SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                userId, roleCode);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // 감사 배선(Track 55) 잔존 방지: 부여 성공 시 actor(caller) 기준 audit_log가 실 커밋되므로 함께 정리(테스트 오염 차단).
                jdbc.update("DELETE FROM audit_log WHERE actor_user_id IN (?, ?)", SUPER_ADMIN_CALLER, OPERATOR_CALLER);
                jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?, ?, ?)",
                        SUPER_ADMIN_CALLER, OPERATOR_CALLER, BUYER_CALLER, TARGET_USER, DUP_TARGET);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?, ?, ?)",
                        SUPER_ADMIN_CALLER, OPERATOR_CALLER, BUYER_CALLER, TARGET_USER, DUP_TARGET);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String body(long userId) {
        return "{\"userId\":" + userId + "}";
    }

    private int adminOperatorMappingCount(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role ur JOIN role r ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND r.code = 'ADMIN_OPERATOR'",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    // audit_log 단건 조회(AuditWiringIntegrationTest 패턴·? 바인딩·SQL injection 없음). 정확히 1건 검증.
    private Map<String, Object> singleAuditRow(String targetType, long targetId, long actorUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, actor_user_id, actor_role, diff_json FROM audit_log "
                        + "WHERE target_type = ? AND target_id = ? AND actor_user_id = ?",
                targetType, targetId, actorUserId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

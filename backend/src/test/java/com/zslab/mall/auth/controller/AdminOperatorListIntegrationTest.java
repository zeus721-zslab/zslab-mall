package com.zslab.mall.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 목록·현재 관리자 조회 E2E 통합 테스트(Track 89-E·실 MariaDB). 조회 인가는 코어스 ADMIN 게이트뿐이라 ADMIN_OPERATOR
 * caller도 목록을 본다(①). 모수는 SUPER_ADMIN·ADMIN_OPERATOR 보유 회원이며 BUYER만 보유한 회원은 제외된다(②). 겸직은 BUYER
 * role 보유로 판정한다(③). startup 부트스트랩 SUPER_ADMIN이 상존하므로 건수 단언은 시드 태그 keyword로 격리한다.
 *
 * <p>시드/정리는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(AdminOperatorControllerIntegrationTest 패턴)로 한다.
 */
@AutoConfigureMockMvc
class AdminOperatorListIntegrationTest extends AbstractIntegrationTest {

    private static final String LIST_URL = "/api/v1/admin/admin-operators";
    private static final String ME_URL = "/api/v1/admin/me";
    private static final String TAG = "T89E";

    private static final long SUPER_CALLER = 9801L;      // SUPER_ADMIN + BUYER(겸직)
    private static final long OPERATOR_CALLER = 9802L;   // ADMIN_OPERATOR만
    private static final long WITHDRAWN_OPERATOR = 9803L; // ADMIN_OPERATOR·탈퇴
    private static final long PLAIN_BUYER = 9804L;       // BUYER만(모수 제외)
    private static final long BUYER_CALLER = 9805L;      // BUYER 토큰(필터 403)

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
        seed(() -> {
            seedUser(SUPER_CALLER, "T89ESA", TAG + " 슈퍼", "t89e-super@test.local", false);
            seedUserRole(SUPER_CALLER, "SUPER_ADMIN");
            seedUserRole(SUPER_CALLER, "BUYER");
            seedUser(OPERATOR_CALLER, "T89EOP", TAG + " 운영", "t89e-operator@test.local", false);
            seedUserRole(OPERATOR_CALLER, "ADMIN_OPERATOR");
            seedUser(WITHDRAWN_OPERATOR, "T89EWD", TAG + " 탈퇴", "t89e-withdrawn@test.local", true);
            seedUserRole(WITHDRAWN_OPERATOR, "ADMIN_OPERATOR");
            seedUser(PLAIN_BUYER, "T89EBY", TAG + " 구매자", "t89e-buyer@test.local", false);
            seedUserRole(PLAIN_BUYER, "BUYER");
        });
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("① 조회 인가: ADMIN_OPERATOR caller도 200(코어스 ADMIN 게이트만) · BUYER 토큰 403")
    void list_adminOperatorCanRead_buyerForbidden() throws Exception {
        mockMvc.perform(get(LIST_URL).param("keyword", TAG).headers(authHeaders.admin(OPERATOR_CALLER)))
                .andExpect(status().isOk());

        mockMvc.perform(get(LIST_URL).headers(authHeaders.buyer(BUYER_CALLER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("② 모수: 기본(ACTIVE) → 시드 중 SUPER_ADMIN·ADMIN_OPERATOR 활성 2건·BUYER만·탈퇴 제외·가입일 desc·역할 배열")
    void list_default_returnsActiveAdminRolesOnly() throws Exception {
        mockMvc.perform(get(LIST_URL).param("keyword", TAG).headers(authHeaders.admin(SUPER_CALLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].userPublicId").value(
                        org.hamcrest.Matchers.containsInAnyOrder(pid("T89ESA"), pid("T89EOP"))))
                .andExpect(jsonPath("$.items[?(@.userPublicId == '" + pid("T89EOP") + "')].roles[0]").value("ADMIN_OPERATOR"))
                .andExpect(jsonPath("$.items[?(@.userPublicId == '" + pid("T89EOP") + "')].withdrawnAt").doesNotExist());
    }

    @Test
    @DisplayName("③ 겸직: SUPER_ADMIN+BUYER 계정 hasBuyerRole true·roles는 ADMIN 계열만 / ADMIN_OPERATOR만 계정 false")
    void list_flagsBuyerRoleAsConcurrent() throws Exception {
        mockMvc.perform(get(LIST_URL).param("keyword", TAG).headers(authHeaders.admin(SUPER_CALLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.userPublicId == '" + pid("T89ESA") + "')].hasBuyerRole").value(true))
                .andExpect(jsonPath("$.items[?(@.userPublicId == '" + pid("T89ESA") + "')].roles").value(
                        org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("SUPER_ADMIN"))))
                .andExpect(jsonPath("$.items[?(@.userPublicId == '" + pid("T89EOP") + "')].hasBuyerRole").value(false));
    }

    @Test
    @DisplayName("④ 필터: role=ADMIN_OPERATOR 1건 · status=WITHDRAWN 탈퇴 1건(withdrawnAt 존재) · keyword 이메일 부분일치 · role 오값 400")
    void list_filters() throws Exception {
        mockMvc.perform(get(LIST_URL).param("keyword", TAG).param("role", "ADMIN_OPERATOR")
                        .headers(authHeaders.admin(SUPER_CALLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].userPublicId").value(pid("T89EOP")));

        mockMvc.perform(get(LIST_URL).param("keyword", TAG).param("status", "WITHDRAWN")
                        .headers(authHeaders.admin(SUPER_CALLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].userPublicId").value(pid("T89EWD")))
                .andExpect(jsonPath("$.items[0].withdrawnAt").exists());

        mockMvc.perform(get(LIST_URL).param("keyword", "t89e-super@").headers(authHeaders.admin(SUPER_CALLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].email").value("t89e-super@test.local"));

        mockMvc.perform(get(LIST_URL).param("role", "BUYER").headers(authHeaders.admin(SUPER_CALLER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("⑤ /admin/me: SUPER_ADMIN+BUYER caller → superAdmin true·roles 전체 / ADMIN_OPERATOR caller → false / BUYER 토큰 403")
    void me_returnsCallerRoles() throws Exception {
        mockMvc.perform(get(ME_URL).headers(authHeaders.admin(SUPER_CALLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userPublicId").value(pid("T89ESA")))
                .andExpect(jsonPath("$.name").value(TAG + " 슈퍼"))
                .andExpect(jsonPath("$.email").value("t89e-super@test.local"))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("SUPER_ADMIN", "BUYER")))
                .andExpect(jsonPath("$.superAdmin").value(true));

        mockMvc.perform(get(ME_URL).headers(authHeaders.admin(OPERATOR_CALLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.superAdmin").value(false))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("ADMIN_OPERATOR")));

        mockMvc.perform(get(ME_URL).headers(authHeaders.buyer(BUYER_CALLER)))
                .andExpect(status().isForbidden());
    }

    // ---------- seed·helpers (? positional 바인딩·SQL injection 없음) ----------

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

    private void seedUser(long id, String tag, String name, String email, boolean withdrawn) {
        jdbc.update("INSERT INTO `user` (id, public_id, name, email, withdrawn_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, DATE_SUB(NOW(6), INTERVAL ? SECOND), NOW(6))",
                id, pid(tag), name, email, withdrawn ? java.time.LocalDateTime.now() : null, id);
    }

    private void seedUserRole(long userId, String roleCode) {
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) "
                        + "SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                userId, roleCode);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?, ?, ?)",
                        SUPER_CALLER, OPERATOR_CALLER, WITHDRAWN_OPERATOR, PLAIN_BUYER, BUYER_CALLER);
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?, ?, ?)",
                        SUPER_CALLER, OPERATOR_CALLER, WITHDRAWN_OPERATOR, PLAIN_BUYER, BUYER_CALLER);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String tag) {
        return "usr_" + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

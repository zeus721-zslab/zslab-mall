package com.zslab.mall.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.adapter.SmsSender;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데모 계정 보호 통합 테스트(D-230·실 MariaDB). 보호 대상 계정의 로그인을 깨뜨리는 조작 6경로(본인 비밀번호 변경·관리자 임시 비밀번호·
 * 본인 탈퇴·관리자 탈퇴·역할 회수·셀러 구성원 제외)가 요청자와 무관하게 403 DEMO_ACCOUNT_PROTECTED이고, 보호 아닌 계정은 기존대로
 * 동작하며, 보호 계정의 이름·연락처 수정은 허용되는지 HTTP 경유로 검증한다.
 *
 * <p>설정값은 대소문자·앞뒤 공백·빈 항목을 섞고, 구매자 데모 계정은 설정과 대소문자가 다른 이메일로 시드해 모든 경로에서 대소문자 무시를
 * 함께 확인한다. 설정이 비면 보호 없음은 DemoAccountGuardTest가 맡는다(기본 테스트 컨텍스트도 빈 값이라 다른 통합 테스트가 방증).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "zslab.demo.protected-emails=demo-admin@zslab.test, Demo-Buyer@zslab.test ,demo-seller@ZSLAB.test,,")
class DemoAccountProtectionIntegrationTest extends AbstractIntegrationTest {

    private static final String PROTECTED_CODE = "DEMO_ACCOUNT_PROTECTED";
    private static final String PROTECTED_MESSAGE = "데모 계정은 이 기능을 사용할 수 없습니다.";
    private static final String PASSWORD = "d230-original-password";
    private static final String PASSWORD_CHANGE_BODY =
            "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"d230-changed-password\"}";
    private static final String REASON_BODY = "{\"reason\":\"D-230 데모 계정 보호 검증\"}";
    private static final String PROFILE_BODY = "{\"name\":\"이름변경\",\"phone\":\"010-2222-3333\"}";

    private static final long DEMO_BUYER = 9761L;   // 보호(설정과 대소문자 다른 이메일)
    private static final long DEMO_SELLER = 9762L;  // 보호 · 셀러 OWNER
    private static final long NORMAL_BUYER = 9763L;
    private static final long NORMAL_STAFF = 9764L; // 보호 아님 · 셀러 STAFF
    private static final long ADMIN_CALLER = 9765L; // SUPER_ADMIN 호출자(보호 아님 · 실제 user 행)
    private static final long ADMIN_DEMO = 9766L;   // 보호 · SUPER_ADMIN(관리자 데모 = 부트스트랩 계정 상정)
    private static final long NORMAL_OWNER = 9767L; // 보호 아님 · 비보호 셀러 OWNER
    private static final long SELLER_ID = 9771L;
    private static final long NORMAL_SELLER_ID = 9772L; // 보호 구성원 없는 셀러(해지 기존 동작)
    private static final String DEMO_BUYER_PID = pid("usr_", "D230DBUY");
    private static final String DEMO_SELLER_PID = pid("usr_", "D230DSLR");
    private static final String NORMAL_BUYER_PID = pid("usr_", "D230NBUY");
    private static final String NORMAL_STAFF_PID = pid("usr_", "D230NSTF");
    private static final String ADMIN_CALLER_PID = pid("usr_", "D230ACLR");
    private static final String ADMIN_DEMO_PID = pid("usr_", "D230DADM");
    private static final String SELLER_PID = pid("slr_", "D230SLR");
    private static final String NORMAL_SELLER_PID = pid("slr_", "D230NSLR");
    private static final String NORMAL_OWNER_PID = pid("usr_", "D230NOWN");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @MockitoBean
    private SmsSender smsSender;

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
    @DisplayName("본인 비밀번호 변경: 구매자·셀러 데모 계정 → 403(비밀번호 불변) / 보호 아닌 구매자 → 204")
    void selfPasswordChange() throws Exception {
        String demoBuyerHash = passwordHash(DEMO_BUYER);
        expectProtected(mockMvc.perform(patch("/api/v1/users/me/password").headers(authHeaders.buyer(DEMO_BUYER))
                .contentType(MediaType.APPLICATION_JSON).content(PASSWORD_CHANGE_BODY)));
        expectProtected(mockMvc.perform(patch("/api/v1/users/me/password").headers(authHeaders.seller(DEMO_SELLER))
                .contentType(MediaType.APPLICATION_JSON).content(PASSWORD_CHANGE_BODY)));
        assertThat(passwordHash(DEMO_BUYER)).isEqualTo(demoBuyerHash);

        mockMvc.perform(patch("/api/v1/users/me/password").headers(authHeaders.buyer(NORMAL_BUYER))
                        .contentType(MediaType.APPLICATION_JSON).content(PASSWORD_CHANGE_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("관리자 임시 비밀번호 발급: 데모 구매자 → 403(비밀번호 불변) / 보호 아닌 구매자 → 200")
    void adminTemporaryPassword() throws Exception {
        String demoBuyerHash = passwordHash(DEMO_BUYER);
        expectProtected(mockMvc.perform(post("/api/v1/admin/members/" + DEMO_BUYER_PID + "/password-reset")
                .headers(authHeaders.admin(ADMIN_CALLER))));
        assertThat(passwordHash(DEMO_BUYER)).isEqualTo(demoBuyerHash);

        mockMvc.perform(post("/api/v1/admin/members/" + NORMAL_BUYER_PID + "/password-reset").headers(authHeaders.admin(ADMIN_CALLER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("본인 탈퇴: 데모 구매자·데모 셀러(셀러 토큰) → 403(탈퇴 미기록) / 보호 아닌 구매자 → 204")
    void selfWithdraw() throws Exception {
        expectProtected(mockMvc.perform(post("/api/v1/users/me/withdraw").headers(authHeaders.buyer(DEMO_BUYER))));
        expectProtected(mockMvc.perform(post("/api/v1/users/me/withdraw").headers(authHeaders.seller(DEMO_SELLER))));
        assertThat(isWithdrawn(DEMO_BUYER)).isFalse();
        assertThat(isWithdrawn(DEMO_SELLER)).isFalse();

        mockMvc.perform(post("/api/v1/users/me/withdraw").headers(authHeaders.buyer(NORMAL_BUYER)))
                .andExpect(status().isNoContent());
        assertThat(isWithdrawn(NORMAL_BUYER)).isTrue();
    }

    @Test
    @DisplayName("관리자 탈퇴: 데모 구매자 → 403(탈퇴 미기록) / 보호 아닌 구매자 → 204")
    void adminWithdraw() throws Exception {
        expectProtected(mockMvc.perform(post("/api/v1/admin/members/" + DEMO_BUYER_PID + "/withdraw")
                .headers(authHeaders.admin(ADMIN_CALLER))));
        assertThat(isWithdrawn(DEMO_BUYER)).isFalse();

        mockMvc.perform(post("/api/v1/admin/members/" + NORMAL_BUYER_PID + "/withdraw").headers(authHeaders.admin(ADMIN_CALLER)))
                .andExpect(status().isNoContent());
        assertThat(isWithdrawn(NORMAL_BUYER)).isTrue();
    }

    @Test
    @DisplayName("역할 회수: 데모 구매자의 BUYER 회수 → 403(역할 유지) / 보호 아닌 구매자 → 204")
    void roleRevoke() throws Exception {
        expectProtected(mockMvc.perform(delete("/api/v1/admin/users/" + DEMO_BUYER_PID + "/roles/BUYER")
                .headers(authHeaders.admin(ADMIN_CALLER)).contentType(MediaType.APPLICATION_JSON).content(REASON_BODY)));
        assertThat(roleCount(DEMO_BUYER, "BUYER")).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/admin/users/" + NORMAL_BUYER_PID + "/roles/BUYER")
                        .headers(authHeaders.admin(ADMIN_CALLER)).contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                .andExpect(status().isNoContent());
        assertThat(roleCount(NORMAL_BUYER, "BUYER")).isZero();
    }

    @Test
    @DisplayName("셀러 구성원 제외: 데모 셀러(OWNER) → 403(소속 유지) / 보호 아닌 STAFF → 204")
    void sellerMemberRemove() throws Exception {
        expectProtected(mockMvc.perform(delete("/api/v1/admin/sellers/" + SELLER_PID + "/members/" + DEMO_SELLER_PID)
                .headers(authHeaders.admin(ADMIN_CALLER)).contentType(MediaType.APPLICATION_JSON).content(REASON_BODY)));
        assertThat(sellerMemberCount(DEMO_SELLER)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/admin/sellers/" + SELLER_PID + "/members/" + NORMAL_STAFF_PID)
                        .headers(authHeaders.admin(ADMIN_CALLER)).contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                .andExpect(status().isNoContent());
        assertThat(sellerMemberCount(NORMAL_STAFF)).isZero();
    }

    @Test
    @DisplayName("셀러 해지: 데모 셀러가 소속된 셀러 TERMINATED → 403(상태·아카이브 불변) / 같은 셀러 SUSPENDED → 200(되돌릴 수 있어 허용) / 보호 구성원 없는 셀러 TERMINATED → 200")
    void sellerTermination() throws Exception {
        expectProtected(mockMvc.perform(patch("/api/v1/admin/sellers/" + SELLER_PID + "/status").headers(authHeaders.admin(ADMIN_CALLER))
                .contentType(MediaType.APPLICATION_JSON).content(statusBody("TERMINATED"))));
        assertThat(sellerStatus(SELLER_ID)).isEqualTo("ACTIVE");
        assertThat(withdrawnSellerCount(SELLER_ID)).isZero();

        mockMvc.perform(patch("/api/v1/admin/sellers/" + SELLER_PID + "/status").headers(authHeaders.admin(ADMIN_CALLER))
                        .contentType(MediaType.APPLICATION_JSON).content(statusBody("SUSPENDED")))
                .andExpect(status().isOk());
        assertThat(sellerStatus(SELLER_ID)).isEqualTo("SUSPENDED");

        mockMvc.perform(patch("/api/v1/admin/sellers/" + NORMAL_SELLER_PID + "/status").headers(authHeaders.admin(ADMIN_CALLER))
                        .contentType(MediaType.APPLICATION_JSON).content(statusBody("TERMINATED")))
                .andExpect(status().isOk());
        assertThat(sellerStatus(NORMAL_SELLER_ID)).isEqualTo("TERMINATED");
    }

    @Test
    @DisplayName("관리자 데모(SUPER_ADMIN): 관리자 토큰 본인 비밀번호 변경·본인 탈퇴 403 · 다른 SUPER_ADMIN의 SUPER_ADMIN 회수 403(호출자·데모 활성 SA 2명 이상이라 마지막 SA 가드와 무관)")
    void adminDemoAccount_protectedOnAllPaths() throws Exception {
        String adminDemoHash = passwordHash(ADMIN_DEMO);
        expectProtected(mockMvc.perform(patch("/api/v1/users/me/password").headers(authHeaders.admin(ADMIN_DEMO))
                .contentType(MediaType.APPLICATION_JSON).content(PASSWORD_CHANGE_BODY)));
        expectProtected(mockMvc.perform(post("/api/v1/users/me/withdraw").headers(authHeaders.admin(ADMIN_DEMO))));
        expectProtected(mockMvc.perform(delete("/api/v1/admin/users/" + ADMIN_DEMO_PID + "/roles/SUPER_ADMIN")
                .headers(authHeaders.admin(ADMIN_CALLER)).contentType(MediaType.APPLICATION_JSON).content(REASON_BODY)));

        assertThat(passwordHash(ADMIN_DEMO)).isEqualTo(adminDemoHash);
        assertThat(isWithdrawn(ADMIN_DEMO)).isFalse();
        assertThat(roleCount(ADMIN_DEMO, "SUPER_ADMIN")).isEqualTo(1);
    }

    @Test
    @DisplayName("보호 계정도 로그인과 무관한 이름·연락처 수정은 허용(본인 200 · 관리자 204)")
    void profileUpdate_allowedForProtectedAccount() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").headers(authHeaders.buyer(DEMO_BUYER))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/members/" + DEMO_BUYER_PID).headers(authHeaders.admin(ADMIN_CALLER))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_BODY))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE id = ?", String.class, DEMO_BUYER)).isEqualTo("이름변경");
    }

    // ---------- helpers ----------

    private static void expectProtected(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(PROTECTED_CODE))
                .andExpect(jsonPath("$.detail").value(PROTECTED_MESSAGE));
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    private String passwordHash(long userId) {
        return jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = ?", String.class, userId);
    }

    private boolean isWithdrawn(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT withdrawn_at IS NOT NULL FROM `user` WHERE id = ?", Boolean.class, userId));
    }

    private int roleCount(long userId, String roleCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_role ur JOIN role r ON ur.role_id = r.id "
                + "WHERE ur.user_id = ? AND r.code = ?", Integer.class, userId, roleCode);
        return count == null ? 0 : count;
    }

    private static String statusBody(String status) {
        return "{\"status\":\"" + status + "\",\"reason\":\"D-230 데모 셀러 해지 보호 검증\"}";
    }

    private String sellerStatus(long sellerId) {
        return jdbc.queryForObject("SELECT status FROM seller WHERE id = ?", String.class, sellerId);
    }

    private int withdrawnSellerCount(long sellerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM withdrawn_seller WHERE original_seller_id = ?", Integer.class, sellerId);
        return count == null ? 0 : count;
    }

    private int sellerMemberCount(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM seller_user WHERE user_id = ? AND seller_id = ?",
                Integer.class, userId, SELLER_ID);
        return count == null ? 0 : count;
    }

    // 모든 SQL은 ? 바인딩·SQL injection 없음.
    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedUser(DEMO_BUYER, DEMO_BUYER_PID, "DEMO-BUYER@zslab.test");
                seedBuyerRole(DEMO_BUYER);
                seedUser(DEMO_SELLER, DEMO_SELLER_PID, "demo-seller@zslab.test");
                seedUser(NORMAL_BUYER, NORMAL_BUYER_PID, "normal-buyer@zslab.test");
                seedBuyerRole(NORMAL_BUYER);
                seedUser(NORMAL_STAFF, NORMAL_STAFF_PID, "normal-staff@zslab.test");
                seedUser(ADMIN_CALLER, ADMIN_CALLER_PID, "admin-caller@zslab.test");
                seedSuperAdminRole(ADMIN_CALLER);
                seedUser(ADMIN_DEMO, ADMIN_DEMO_PID, "demo-admin@zslab.test");
                seedSuperAdminRole(ADMIN_DEMO);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'D230데모셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID, SELLER_PID);
                seedSellerUser(DEMO_SELLER, SELLER_ID, "SELLER_OWNER");
                seedSellerUser(NORMAL_STAFF, SELLER_ID, "SELLER_STAFF");
                seedUser(NORMAL_OWNER, NORMAL_OWNER_PID, "normal-owner@zslab.test");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'D230비보호셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", NORMAL_SELLER_ID, NORMAL_SELLER_PID);
                seedSellerUser(NORMAL_OWNER, NORMAL_SELLER_ID, "SELLER_OWNER");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedUser(long id, String publicId, String email) {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, password_hash, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '데모보호', '010-1111-2222', ?, NOW(6), NOW(6))",
                id, publicId, email, passwordEncoder.encode(PASSWORD));
    }

    private void seedBuyerRole(long userId) {
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'BUYER'", userId);
        jdbc.update("INSERT INTO buyer_profile (user_id, grade_id, grade_source, created_at, updated_at) "
                + "VALUES (?, (SELECT id FROM buyer_grade WHERE code = 'SILVER'), 'AUTO', NOW(6), NOW(6))", userId);
    }

    private void seedSuperAdminRole(long userId) {
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'SUPER_ADMIN'", userId);
    }

    private void seedSellerUser(long userId, long sellerId, String roleCode) {
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = ?", userId, sellerId, roleCode);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (long sellerId : List.of(SELLER_ID, NORMAL_SELLER_ID)) {
                    jdbc.update("DELETE FROM audit_log WHERE target_type = 'SELLER' AND target_id = ?", sellerId);
                    jdbc.update("DELETE FROM withdrawn_seller WHERE original_seller_id = ?", sellerId);
                    jdbc.update("DELETE FROM seller_user WHERE seller_id = ?", sellerId);
                    jdbc.update("DELETE FROM seller WHERE id = ?", sellerId);
                }
                for (long id : List.of(DEMO_BUYER, DEMO_SELLER, NORMAL_BUYER, NORMAL_STAFF, ADMIN_CALLER, ADMIN_DEMO, NORMAL_OWNER)) {
                    jdbc.update("DELETE FROM audit_log WHERE target_type = 'USER' AND target_id = ?", id);
                    jdbc.update("DELETE FROM notification_log WHERE target_type = 'USER' AND target_id = ?", id);
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

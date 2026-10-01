package com.zslab.mall.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthCookies;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공개 관리자 데모 세션 제한 통합 테스트(최종 점검 K1·실 MariaDB). 데모 로그인 대행이 보낸 표식(publicDemo)으로 발급된 관리자 토큰은 계정·권한
 * 변경 9종(임시 비밀번호·회원 탈퇴·역할 회수·운영 관리자 부여·셀러 구성원 추가·제외·역할 변경·입점 OWNER 지정·시더 2개)이 403
 * DEMO_SESSION_RESTRICTED이고 상태가 바뀌지 않으며, 업무 처리(회원 정보 수정·OWNER 없는 입점)는 그대로 되고, 같은 계정이라도 표식 없이
 * 비밀번호로 로그인한 세션은 막히지 않는지 HTTP 경유로 검증한다.
 */
@AutoConfigureMockMvc
class PublicDemoSessionGuardIntegrationTest extends AbstractIntegrationTest {

    private static final String RESTRICTED_CODE = "DEMO_SESSION_RESTRICTED";
    private static final String RESTRICTED_MESSAGE = "공개 데모 세션에서는 계정·권한 변경과 데모 데이터 생성을 할 수 없습니다.";
    private static final String PASSWORD = "k1-demo-session-password";
    private static final String ADMIN_EMAIL = "k1-admin@zslab.test";
    private static final String REASON_BODY = "{\"reason\":\"K1 데모 세션 제한 검증\"}";
    private static final String PROFILE_BODY = "{\"name\":\"이름변경\",\"phone\":\"010-2222-3333\"}";

    private static final long ADMIN = 9571L;  // SUPER_ADMIN · 데모 세션·직접 로그인 양쪽의 계정
    private static final long BUYER = 9572L;  // 보호 아닌 구매자(연락처 있음)
    private static final long OWNER = 9573L;
    private static final long STAFF = 9574L;
    private static final long SELLER_ID = 9575L;
    private static final String BUYER_PID = pid("usr_", "K1DBUY");
    private static final String STAFF_PID = pid("usr_", "K1DSTF");
    private static final String SELLER_PID = pid("slr_", "K1DSLR");
    private static final String OWNER_PROVISION_COMPANY = "K1데모세션OWNER입점";
    private static final String PLAIN_PROVISION_COMPANY = "K1데모세션일반입점";

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
    @DisplayName("데모 표식 세션: 차단 9종(10 엔드포인트) 모두 403 DEMO_SESSION_RESTRICTED · 비밀번호·탈퇴·역할·소속·입점 불변")
    void publicDemoSession_blocksAccountAndRoleChanges() throws Exception {
        String passwordHash = passwordHash(BUYER);
        List<MockHttpServletRequestBuilder> restricted = List.of(
                post("/api/v1/admin/members/" + BUYER_PID + "/password-reset"),
                post("/api/v1/admin/members/" + BUYER_PID + "/withdraw"),
                delete("/api/v1/admin/users/" + BUYER_PID + "/roles/BUYER")
                        .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY),
                post("/api/v1/admin/admin-operators")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userPublicId\":\"" + BUYER_PID + "\"}"),
                post("/api/v1/admin/sellers/" + SELLER_PID + "/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userPublicId\":\"" + BUYER_PID + "\",\"role\":\"SELLER_STAFF\"}"),
                delete("/api/v1/admin/sellers/" + SELLER_PID + "/members/" + STAFF_PID)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY),
                patch("/api/v1/admin/sellers/" + SELLER_PID + "/members/" + STAFF_PID + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SELLER_MANAGER\",\"reason\":\"K1 데모 세션 제한 검증\"}"),
                post("/api/v1/admin/sellers").contentType(MediaType.APPLICATION_JSON).content(provisionBody(OWNER_PROVISION_COMPANY, BUYER_PID)),
                post("/api/v1/admin/demo-seed/product-questions").param("dryRun", "true"),
                post("/api/v1/admin/demo-seed/reviews").param("dryRun", "true"));

        for (MockHttpServletRequestBuilder request : restricted) {
            mockMvc.perform(request.with(authHeaders.publicDemoAdmin(ADMIN)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(RESTRICTED_CODE))
                    .andExpect(jsonPath("$.detail").value(RESTRICTED_MESSAGE));
        }

        assertThat(passwordHash(BUYER)).isEqualTo(passwordHash);
        assertThat(jdbc.queryForObject("SELECT withdrawn_at IS NULL FROM `user` WHERE id = ?", Boolean.class, BUYER)).isTrue();
        assertThat(roleCount(BUYER, "BUYER")).isEqualTo(1);
        assertThat(roleCount(BUYER, "ADMIN_OPERATOR")).isZero();
        assertThat(count("SELECT COUNT(*) FROM seller_user WHERE user_id = ?", BUYER)).isZero();
        assertThat(count("SELECT COUNT(*) FROM seller_user su JOIN role r ON su.role_id = r.id "
                + "WHERE su.user_id = ? AND su.seller_id = ? AND r.code = 'SELLER_STAFF'", STAFF, SELLER_ID)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM seller WHERE company_name = ?", OWNER_PROVISION_COMPANY)).isZero();
    }

    @Test
    @DisplayName("데모 표식 세션도 업무 처리는 허용: 회원 정보 수정 204 · OWNER 없는 입점 201")
    void publicDemoSession_allowsBusinessOperations() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/members/" + BUYER_PID).with(authHeaders.publicDemoAdmin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_BODY))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/admin/sellers").with(authHeaders.publicDemoAdmin(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(provisionBody(PLAIN_PROVISION_COMPANY, null)))
                .andExpect(status().isCreated());
        assertThat(count("SELECT COUNT(*) FROM seller WHERE company_name = ?", PLAIN_PROVISION_COMPANY)).isEqualTo(1);
    }

    @Test
    @DisplayName("로그인 경로: publicDemo=true 로그인 쿠키 → 임시 비밀번호 403(비밀번호 불변) / 같은 계정 표식 없는 로그인 쿠키 → 200")
    void loginWithPublicDemoFlag_marksSession_plainLoginUnaffected() throws Exception {
        String passwordHash = passwordHash(BUYER);
        Cookie demoCookie = adminLoginCookie(",\"publicDemo\":true");
        mockMvc.perform(post("/api/v1/admin/members/" + BUYER_PID + "/password-reset").cookie(demoCookie).with(authHeaders.csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(RESTRICTED_CODE));
        assertThat(passwordHash(BUYER)).isEqualTo(passwordHash);

        Cookie plainCookie = adminLoginCookie("");
        mockMvc.perform(post("/api/v1/admin/members/" + BUYER_PID + "/password-reset").cookie(plainCookie).with(authHeaders.csrf()))
                .andExpect(status().isOk());
        assertThat(passwordHash(BUYER)).isNotEqualTo(passwordHash);
    }

    // ---------- helpers ----------

    /** 관리자 로그인 응답 Set-Cookie에서 관리자 쿠키를 꺼낸다. @param extraBodyFields 본문에 이어 붙일 필드(앞 쉼표 포함) */
    private Cookie adminLoginCookie(String extraBodyFields) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/auth/login").with(authHeaders.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + PASSWORD + "\"" + extraBodyFields + "}"))
                .andExpect(status().isOk())
                .andReturn();
        String prefix = AuthCookies.ADMIN_COOKIE + "=";
        String setCookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(prefix))
                .findFirst()
                .orElseThrow();
        return new Cookie(AuthCookies.ADMIN_COOKIE, setCookie.substring(prefix.length(), setCookie.indexOf(';')));
    }

    /** @param ownerUserPublicId null이면 OWNER 없는 입점 */
    private static String provisionBody(String companyName, String ownerUserPublicId) {
        String owner = ownerUserPublicId == null ? "" : ",\"ownerUserPublicId\":\"" + ownerUserPublicId + "\"";
        return "{\"companyName\":\"" + companyName + "\",\"ceoName\":\"대표\",\"status\":\"ACTIVE\"" + owner + "}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    private String passwordHash(long userId) {
        return jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = ?", String.class, userId);
    }

    private int roleCount(long userId, String roleCode) {
        return count("SELECT COUNT(*) FROM user_role ur JOIN role r ON ur.role_id = r.id WHERE ur.user_id = ? AND r.code = ?",
                userId, roleCode);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    // 모든 SQL은 ? 바인딩·SQL injection 없음.
    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedUser(ADMIN, pid("usr_", "K1DADM"), ADMIN_EMAIL);
                seedRole(ADMIN, "SUPER_ADMIN");
                seedUser(BUYER, BUYER_PID, "k1-buyer@zslab.test");
                seedRole(BUYER, "BUYER");
                jdbc.update("INSERT INTO buyer_profile (user_id, grade_id, grade_source, created_at, updated_at) "
                        + "VALUES (?, (SELECT id FROM buyer_grade WHERE code = 'SILVER'), 'AUTO', NOW(6), NOW(6))", BUYER);
                seedUser(OWNER, pid("usr_", "K1DOWN"), "k1-owner@zslab.test");
                seedUser(STAFF, STAFF_PID, "k1-staff@zslab.test");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'K1데모세션셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID, SELLER_PID);
                seedSellerUser(OWNER, "SELLER_OWNER");
                seedSellerUser(STAFF, "SELLER_STAFF");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedUser(long id, String publicId, String email) {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, password_hash, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'K1데모세션', '010-1111-2222', ?, NOW(6), NOW(6))",
                id, publicId, email, passwordEncoder.encode(PASSWORD));
    }

    private void seedRole(long userId, String roleCode) {
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?", userId, roleCode);
    }

    private void seedSellerUser(long userId, String roleCode) {
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = ?", userId, SELLER_ID, roleCode);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                // 가드가 없을 때(RED) 생성될 수 있는 입점 셀러도 정리해 다음 실행을 오염시키지 않는다.
                List<Long> sellerIds = new ArrayList<>(List.of(SELLER_ID));
                sellerIds.addAll(jdbc.queryForList("SELECT id FROM seller WHERE company_name IN (?, ?)", Long.class,
                        OWNER_PROVISION_COMPANY, PLAIN_PROVISION_COMPANY));
                for (long sellerId : sellerIds) {
                    jdbc.update("DELETE FROM audit_log WHERE target_type = 'SELLER' AND target_id = ?", sellerId);
                    jdbc.update("DELETE FROM seller_user WHERE seller_id = ?", sellerId);
                    jdbc.update("DELETE FROM seller WHERE id = ?", sellerId);
                }
                for (long id : List.of(ADMIN, BUYER, OWNER, STAFF)) {
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

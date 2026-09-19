package com.zslab.mall.seller.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 셀러 구성원 관리 통합 테스트(Track 89-G·D-189·실 MariaDB·HTTP 경유·실 커밋). 이 트랙의 핵심 전제 — "셀러 판정은 seller_user 행
 * 존재 하나로 결정되고, 리졸버가 매 요청 DB를 읽어 제거 즉시 401이 된다" — 를 T2·T3가 기발급 토큰으로 직접 증명한다.
 *
 * <p>SmsSender는 MockitoBean(기본 no-op·T7-2에서 예외 주입). 트랜잭션: 명령의 커밋·롤백을 JdbcTemplate로 검증하므로 클래스에
 * {@code @Transactional}을 두지 않는다. 모든 SQL은 ? positional 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class AdminSellerMemberControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/sellers";
    private static final String SELLER_API = "/api/v1/seller/settlements"; // 셀러 전용 읽기 API(리졸버 통과 여부 관측용)
    private static final String BUYER_API = "/api/v1/users/me";

    private static final long ADMIN_ID = 8880L;
    private static final long BUYER_ID = 8881L;

    private static final long S_A = 8861L;   // 활성 OWNER 1(OWNER_A) + 탈퇴 OWNER 1(WDR_A) → 마지막 활성 OWNER 가드 경계
    private static final long S_B = 8862L;   // 활성 OWNER 1(OWNER_B) + 활성 MANAGER 1(MGR_B)
    private static final long S_C = 8863L;   // 구성원 0(셀러 1 형태)
    private static final long S_W = 8864L;   // 탈퇴 OWNER만(WDR_W) — 셀러 2 형태
    private static final long[] SELLER_IDS = {S_A, S_B, S_C, S_W};

    private static final long OWNER_A = 8871L;
    private static final long WDR_A = 8872L;
    private static final long OWNER_B = 8873L;
    private static final long MGR_B = 8874L;
    private static final long WDR_W = 8875L;
    private static final long FREE = 8876L;       // 미소속 활성 회원(비밀번호 있음·로그인 검증)
    private static final long WDR_FREE = 8877L;   // 미소속 탈퇴 회원
    private static final long[] USER_IDS = {OWNER_A, WDR_A, OWNER_B, MGR_B, WDR_W, FREE, WDR_FREE};

    private static final String FREE_EMAIL = "free@89g.test";
    private static final String FREE_PASSWORD = "Free-Pass-1234";
    private static final String NEW_EMAIL = "newbie@89g.test";
    private static final String NEW_PHONE = "010-8900-0001";
    private static final String MISSING_USER_PID = pid("usr_", "89GMISS");

    @MockitoBean
    private SmsSender smsSender;

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
    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        doNothing().when(smsSender).send(any(), any());
        cleanup();
        seedAll();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ==================== T1 권한 ====================

    @Test
    @DisplayName("T1 권한: 추가·제거·역할 변경 — 무인증 401 / BUYER 403 / ADMIN 201·204")
    void authorization() throws Exception {
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").contentType(MediaType.APPLICATION_JSON)
                .content(addBody(upid(FREE), "SELLER_STAFF"))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.buyer(BUYER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(FREE), "SELLER_STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B)).headers(authHeaders.buyer(BUYER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(reasonBody("x"))).andExpect(status().isForbidden());
        mockMvc.perform(patch(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B) + "/role").headers(authHeaders.buyer(BUYER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_STAFF", "x"))).andExpect(status().isForbidden());
        assertThat(memberCount(S_C)).isZero();

        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(FREE), "SELLER_STAFF")))
                .andExpect(status().isCreated());
        mockMvc.perform(patch(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B) + "/role").headers(authHeaders.admin(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_STAFF", "직급 변경"))).andExpect(status().isNoContent());
        mockMvc.perform(delete(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B)).headers(authHeaders.admin(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON).content(reasonBody("퇴사"))).andExpect(status().isNoContent());
    }

    // ==================== T2 추가 → 즉시 셀러 접근 ====================

    @Test
    @DisplayName("T2 추가 즉시 접근: 기발급 SELLER 토큰이 추가 전 401 → 추가 후 같은 토큰으로 200(재로그인 불필요) · SELLER 로그인 200 · 응답·행·감사 CREATE")
    void add_grantsSellerAccessImmediately() throws Exception {
        HttpHeaders preIssuedSellerToken = authHeaders.seller(FREE);
        // 추가 전: 토큰은 유효하나 seller_user 매핑이 없어 리졸버가 401(fail-closed)
        mockMvc.perform(get(SELLER_API).headers(preIssuedSellerToken)).andExpect(status().isUnauthorized());
        // 로그인도 ROLE_MISMATCH 401
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(FREE_EMAIL, FREE_PASSWORD, "SELLER"))).andExpect(status().isUnauthorized());

        String body = mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(FREE), "SELLER_MANAGER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userPublicId").value(upid(FREE)))
                .andExpect(jsonPath("$.email").value(FREE_EMAIL))
                .andExpect(jsonPath("$.name").value("자유회원"))
                .andExpect(jsonPath("$.roleCode").value("SELLER_MANAGER"))
                .andExpect(jsonPath("$.withdrawnAt").doesNotExist())
                .andExpect(jsonPath("$.joinedAt").exists())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("password");

        // 추가 후: 같은 기발급 토큰으로 셀러 API 200(매 요청 DB 조회) · 로그인도 200
        mockMvc.perform(get(SELLER_API).headers(preIssuedSellerToken)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(FREE_EMAIL, FREE_PASSWORD, "SELLER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").exists());

        assertThat(memberRole(S_C, FREE)).isEqualTo("SELLER_MANAGER");
        // 셀러 상세 구성원 목록에 joinedAt 포함
        mockMvc.perform(get(URL + "/" + pid(S_C)).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].userPublicId").value(upid(FREE)))
                .andExpect(jsonPath("$.members[0].joinedAt").exists());
        JsonNode audit = singleAuditDiff("CREATE", "SELLER", S_C);
        assertThat(audit.path("userId").path("after").asLong()).isEqualTo(FREE);
        assertThat(audit.path("roleCode").path("after").asText()).isEqualTo("SELLER_MANAGER");
        assertThat(audit.path("newUserCreated").path("after").asBoolean()).isFalse();
    }

    // ==================== T3 제거 → 즉시 401·BUYER 세션 유지 ====================

    @Test
    @DisplayName("T3 제거 즉시 차단: 기발급 SELLER 토큰 200 → 제거(204·사유·감사 DELETE) → 같은 토큰 즉시 401 · BUYER 토큰은 계속 200 · credentials_changed_at 불변(NULL)")
    void remove_revokesSellerAccessImmediately_keepsBuyerSession() throws Exception {
        HttpHeaders sellerToken = authHeaders.seller(MGR_B);
        HttpHeaders buyerToken = authHeaders.buyer(MGR_B);
        mockMvc.perform(get(SELLER_API).headers(sellerToken)).andExpect(status().isOk());
        mockMvc.perform(get(BUYER_API).headers(buyerToken)).andExpect(status().isOk());

        mockMvc.perform(delete(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("퇴사 처리")))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_user WHERE user_id = ?", Integer.class, MGR_B)).isZero();
        // 토큰 무효화 장치 없이 리졸버의 매 요청 DB 조회만으로 즉시 401
        mockMvc.perform(get(SELLER_API).headers(sellerToken)).andExpect(status().isUnauthorized());
        // BUYER 겸직 세션은 유지 — credentials_changed_at을 건드리지 않았음을 DB로도 단언
        mockMvc.perform(get(BUYER_API).headers(buyerToken)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT credentials_changed_at FROM `user` WHERE id = ?", Timestamp.class, MGR_B)).isNull();
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM `user` WHERE id = ?", Timestamp.class, MGR_B)).isNull();

        JsonNode audit = singleAuditDiff("DELETE", "SELLER", S_B);
        assertThat(audit.path("userId").path("before").asLong()).isEqualTo(MGR_B);
        assertThat(audit.path("roleCode").path("before").asText()).isEqualTo("SELLER_MANAGER");
        assertThat(audit.path("reason").path("after").asText()).isEqualTo("퇴사 처리");

        // R1 외부 검토 지적 1: 미존재 계정·타 셀러 소속 계정 모두 같은 404 SELLER_MEMBER_NOT_FOUND(계정 존재 여부 비노출·Track 53/84/89-F 정책)
        mockMvc.perform(delete(URL + "/" + pid(S_B) + "/members/" + MISSING_USER_PID).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("없는 계정")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_MEMBER_NOT_FOUND"));
        mockMvc.perform(delete(URL + "/" + pid(S_B) + "/members/" + upid(OWNER_A)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("타 셀러 소속")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_MEMBER_NOT_FOUND"));
        assertThat(memberRole(S_A, OWNER_A)).isEqualTo("SELLER_OWNER");
        // 제거 후 재추가 가능(hard-delete·잔존 없음) · 사유 blank 400 · 구성원 아님 404
        mockMvc.perform(delete(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("다시")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_MEMBER_NOT_FOUND"));
        mockMvc.perform(delete(URL + "/" + pid(S_B) + "/members/" + upid(OWNER_B)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("  ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL + "/" + pid(S_B) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(MGR_B), "SELLER_STAFF")))
                .andExpect(status().isCreated());
    }

    // ==================== T4 추가 거부 ====================

    @Test
    @DisplayName("T4 추가 거부: 타 셀러 소속 409 · 같은 셀러 재추가 409 · 탈퇴 회원 409 · 회원 미존재 404 · 셀러 미존재 404 · 역할 오값 400 — 전부 행 불변")
    void add_rejections() throws Exception {
        int before = totalMemberCount();
        mockMvc.perform(post(URL + "/" + pid(S_A) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(OWNER_B), "SELLER_STAFF")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_USER_ALREADY_EXISTS"));
        mockMvc.perform(post(URL + "/" + pid(S_B) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(OWNER_B), "SELLER_STAFF")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_USER_ALREADY_EXISTS"));
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(WDR_FREE), "SELLER_STAFF")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_ALREADY_WITHDRAWN"));
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(MISSING_USER_PID, "SELLER_STAFF")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(post(URL + "/" + pid("slr_", "89GMISS") + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(FREE), "SELLER_STAFF")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(FREE), "BUYER")))
                .andExpect(status().isBadRequest());
        assertThat(totalMemberCount()).isEqualTo(before);
    }

    // ==================== T5 마지막 OWNER 가드 ====================

    @Test
    @DisplayName("T5 마지막 활성 OWNER: 제거 409·강등 409(탈퇴 OWNER는 세지 않음) · 탈퇴 OWNER 행 제거 204(셀러 2 형태) · 다른 OWNER 추가 후 제거 204")
    void lastOwnerGuard() throws Exception {
        // S_A: 활성 OWNER_A + 탈퇴 WDR_A(OWNER) — WDR_A는 활성이 아니므로 OWNER_A가 마지막 활성 OWNER
        mockMvc.perform(delete(URL + "/" + pid(S_A) + "/members/" + upid(OWNER_A)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("정리")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_LAST_OWNER"));
        mockMvc.perform(patch(URL + "/" + pid(S_A) + "/members/" + upid(OWNER_A) + "/role").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_STAFF", "강등")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_LAST_OWNER"));
        assertThat(memberRole(S_A, OWNER_A)).isEqualTo("SELLER_OWNER");

        // 탈퇴 OWNER 행 제거는 허용(기능하는 대표가 줄지 않음) — 셀러 2 정리 시나리오
        mockMvc.perform(delete(URL + "/" + pid(S_A) + "/members/" + upid(WDR_A)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("탈퇴 회원 정리")))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(URL + "/" + pid(S_W) + "/members/" + upid(WDR_W)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("탈퇴 회원 정리")))
                .andExpect(status().isNoContent());
        assertThat(memberCount(S_W)).isZero();

        // 다른 활성 OWNER를 추가하면 기존 OWNER 제거·강등 가능
        mockMvc.perform(post(URL + "/" + pid(S_A) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(upid(FREE), "SELLER_OWNER")))
                .andExpect(status().isCreated());
        mockMvc.perform(patch(URL + "/" + pid(S_A) + "/members/" + upid(OWNER_A) + "/role").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_MANAGER", "대표 교체")))
                .andExpect(status().isNoContent());
        assertThat(memberRole(S_A, OWNER_A)).isEqualTo("SELLER_MANAGER");
        // 이제 FREE가 마지막 활성 OWNER → 제거 409, OWNER_A(MANAGER) 제거는 204
        mockMvc.perform(delete(URL + "/" + pid(S_A) + "/members/" + upid(FREE)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("x")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_LAST_OWNER"));
        mockMvc.perform(delete(URL + "/" + pid(S_A) + "/members/" + upid(OWNER_A)).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(reasonBody("정리")))
                .andExpect(status().isNoContent());
    }

    // ==================== T6 역할 변경 ====================

    @Test
    @DisplayName("T6 역할 변경: MANAGER→STAFF 204·행·감사 UPDATE(before/after/reason) · 같은 역할 422 · 사유 blank 400 · 오값 400 · 구성원 아님 404")
    void changeRole() throws Exception {
        mockMvc.perform(patch(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B) + "/role").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_STAFF", "직급 조정")))
                .andExpect(status().isNoContent());
        assertThat(memberRole(S_B, MGR_B)).isEqualTo("SELLER_STAFF");
        JsonNode audit = singleAuditDiff("UPDATE", "SELLER", S_B);
        assertThat(audit.path("roleCode").path("before").asText()).isEqualTo("SELLER_MANAGER");
        assertThat(audit.path("roleCode").path("after").asText()).isEqualTo("SELLER_STAFF");
        assertThat(audit.path("reason").path("after").asText()).isEqualTo("직급 조정");

        mockMvc.perform(patch(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B) + "/role").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_STAFF", "재요청")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SELLER_MEMBER_INVALID_STATE"));
        mockMvc.perform(patch(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B) + "/role").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_MANAGER", " ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(URL + "/" + pid(S_B) + "/members/" + upid(MGR_B) + "/role").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody("ADMIN_OPERATOR", "승격")))
                .andExpect(status().isBadRequest());
        // OWNER_A는 S_A 소속 → S_B 경로에서는 404(타 셀러 소속 은닉)
        mockMvc.perform(patch(URL + "/" + pid(S_B) + "/members/" + upid(OWNER_A) + "/role").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_STAFF", "x")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_MEMBER_NOT_FOUND"));
        // R1 외부 검토 지적 1: 미존재 계정도 타 셀러 소속과 같은 404 SELLER_MEMBER_NOT_FOUND(USER_NOT_FOUND로 갈리지 않음)
        mockMvc.perform(patch(URL + "/" + pid(S_B) + "/members/" + MISSING_USER_PID + "/role").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody("SELLER_STAFF", "x")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELLER_MEMBER_NOT_FOUND"));
        assertThat(memberRole(S_B, MGR_B)).isEqualTo("SELLER_STAFF");
        assertThat(memberRole(S_A, OWNER_A)).isEqualTo("SELLER_OWNER");
    }

    // ==================== T7 미가입자 계정 생성 ====================

    @Test
    @DisplayName("T7-1 미가입자: newUser → 201·user 생성(BUYER role·buyer_profile·password_hash·변경 강제 1)·seller_user 연결·SMS 1회·로그 마스킹·응답에 비밀번호 없음·감사 CREATE USER + CREATE SELLER(newUserCreated)")
    void add_newUser_createsAccountAndMembership() throws Exception {
        String body = mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addNewUserBody(NEW_EMAIL, "신규대표", NEW_PHONE, "SELLER_OWNER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userPublicId").exists())
                .andExpect(jsonPath("$.email").value(NEW_EMAIL))
                .andExpect(jsonPath("$.roleCode").value("SELLER_OWNER"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContainIgnoringCase("password");

        Map<String, Object> user = jdbc.queryForMap("SELECT id, public_id, name, phone, password_hash, password_change_required, "
                + "credentials_changed_at, withdrawn_at FROM `user` WHERE email = ?", NEW_EMAIL);
        long userId = ((Number) user.get("id")).longValue();
        assertThat(user.get("name")).isEqualTo("신규대표");
        assertThat(user.get("phone")).isEqualTo(NEW_PHONE);
        assertThat((String) user.get("password_hash")).isNotBlank();
        assertThat(user.get("password_change_required")).isEqualTo(Boolean.TRUE); // TINYINT(1) → 드라이버가 Boolean으로 매핑
        assertThat(user.get("withdrawn_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.code = 'BUYER'",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM buyer_profile WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(memberRole(S_C, userId)).isEqualTo("SELLER_OWNER");

        // 임시 비밀번호 SMS: 원문 1회 발송·저장본은 마스킹(평문 없음)
        verify(smsSender).send(any(), any());
        Map<String, Object> smsLog = jdbc.queryForMap("SELECT status, template_code, content FROM notification_log "
                + "WHERE recipient_user_id = ? AND template_code = 'TPL_TEMPORARY_PASSWORD'", userId);
        assertThat(smsLog.get("status")).isEqualTo("SENT");
        assertThat((String) smsLog.get("content")).contains("****");
        // 감사: USER CREATE(최소셋) + SELLER CREATE(newUserCreated=true)
        JsonNode userAudit = singleAuditDiff("CREATE", "USER", userId);
        assertThat(userAudit.path("role").path("after").asText()).isEqualTo("BUYER");
        assertThat(userAudit.path("passwordChangeRequired").path("after").asBoolean()).isTrue();
        assertThat(userAudit.toString()).doesNotContain(NEW_EMAIL).doesNotContain(NEW_PHONE);
        JsonNode sellerAudit = singleAuditDiff("CREATE", "SELLER", S_C);
        assertThat(sellerAudit.path("newUserCreated").path("after").asBoolean()).isTrue();
        assertThat(sellerAudit.path("userId").path("after").asLong()).isEqualTo(userId);

        // 생성된 계정으로 SELLER 로그인 가능 여부는 임시 비밀번호를 모르므로 검증하지 않는다(평문 미노출 계약). 매핑만으로 셀러 API는 열린다.
        mockMvc.perform(get(SELLER_API).headers(authHeaders.seller(userId))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T7-2 미가입자 실패: 이메일 중복 409(탈퇴 회원 이메일 포함) · XOR 위반 400(둘 다·둘 다 없음) · 형식 400 · SMS 실패 502 → 계정·매핑 전체 롤백")
    void add_newUser_rejections() throws Exception {
        int usersBefore = userCount();
        int membersBefore = totalMemberCount();
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addNewUserBody(FREE_EMAIL, "중복", NEW_PHONE, "SELLER_STAFF")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addNewUserBody("wdrfree@89g.test", "중복", NEW_PHONE, "SELLER_STAFF")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        // XOR: 둘 다 / 둘 다 없음
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userPublicId\":\"" + upid(FREE) + "\",\"newUser\":{\"email\":\"" + NEW_EMAIL
                                + "\",\"name\":\"둘다\",\"phone\":\"" + NEW_PHONE + "\"},\"role\":\"SELLER_STAFF\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SELLER_STAFF\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 형식: 이메일 형식·phone 누락
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addNewUserBody("not-an-email", "형식", NEW_PHONE, "SELLER_STAFF")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUser\":{\"email\":\"" + NEW_EMAIL + "\",\"name\":\"무연락\"},\"role\":\"SELLER_STAFF\"}"))
                .andExpect(status().isBadRequest());
        assertThat(userCount()).isEqualTo(usersBefore);
        assertThat(totalMemberCount()).isEqualTo(membersBefore);

        // SMS 실패 → 502·계정·역할·프로필·매핑 전부 롤백(Track 84 정책 그대로)
        doThrow(new IllegalStateException("SMS 게이트웨이 장애")).when(smsSender).send(any(), any());
        mockMvc.perform(post(URL + "/" + pid(S_C) + "/members").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(addNewUserBody(NEW_EMAIL, "신규", NEW_PHONE, "SELLER_STAFF")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("TEMPORARY_PASSWORD_DELIVERY_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email = ?", Integer.class, NEW_EMAIL)).isZero();
        assertThat(memberCount(S_C)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_user_id = ?", Integer.class, ADMIN_ID)).isZero();
    }

    // ---------- helpers ----------

    private static String pid(long sellerId) {
        return pid("slr_", "89GS" + sellerId);
    }

    private static String upid(long userId) {
        return pid("usr_", "89GU" + userId);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }

    private static String addBody(String userPublicId, String role) {
        return "{\"userPublicId\":\"" + userPublicId + "\",\"role\":\"" + role + "\"}";
    }

    private static String addNewUserBody(String email, String name, String phone, String role) {
        return "{\"newUser\":{\"email\":\"" + email + "\",\"name\":\"" + name + "\",\"phone\":\"" + phone + "\"},\"role\":\"" + role + "\"}";
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String roleBody(String role, String reason) {
        return "{\"role\":\"" + role + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String loginBody(String email, String password, String role) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}";
    }

    private int memberCount(long sellerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM seller_user WHERE seller_id = ?", Integer.class, sellerId);
    }

    private int totalMemberCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM seller_user WHERE seller_id BETWEEN 8861 AND 8864", Integer.class);
    }

    private int userCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email LIKE '%@89g.test'", Integer.class);
    }

    private String memberRole(long sellerId, long userId) {
        return jdbc.queryForObject("SELECT r.code FROM seller_user su JOIN role r ON r.id = su.role_id "
                + "WHERE su.seller_id = ? AND su.user_id = ?", String.class, sellerId, userId);
    }

    private JsonNode singleAuditDiff(String action, String targetType, long targetId) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT diff_json FROM audit_log WHERE actor_user_id = ? AND action = ? AND target_type = ? AND target_id = ?",
                ADMIN_ID, action, targetType, targetId);
        assertThat(rows).hasSize(1);
        return objectMapper.readTree((String) rows.get(0).get("diff_json"));
    }

    // ---------- seed (모든 INSERT는 ? positional 바인딩·정적 SQL) ----------

    private void seedAll() {
        String freeHash = passwordEncoder.encode(FREE_PASSWORD);
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedUser(OWNER_A, "ownera@89g.test", "대표A", null, null);
                seedUser(WDR_A, "wdra@89g.test", "전대표A", null, Timestamp.valueOf("2026-09-01 00:00:00"));
                seedUser(OWNER_B, "ownerb@89g.test", "대표B", null, null);
                seedUser(MGR_B, "mgrb@89g.test", "매니저B", null, null);
                seedUser(WDR_W, "wdrw@89g.test", "전대표W", null, Timestamp.valueOf("2026-09-01 00:00:00"));
                seedUser(FREE, FREE_EMAIL, "자유회원", freeHash, null);
                seedUser(WDR_FREE, "wdrfree@89g.test", "탈퇴회원", null, Timestamp.valueOf("2026-09-01 00:00:00"));

                for (long sellerId : SELLER_IDS) {
                    jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, '대표', 'ACTIVE', NOW(6), NOW(6))", sellerId, pid(sellerId), "89G셀러" + sellerId);
                }
                seedMember(S_A, OWNER_A, "SELLER_OWNER");
                seedMember(S_A, WDR_A, "SELLER_OWNER");
                seedMember(S_B, OWNER_B, "SELLER_OWNER");
                seedMember(S_B, MGR_B, "SELLER_MANAGER");
                seedMember(S_W, WDR_W, "SELLER_OWNER");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedUser(long id, String email, String name, String passwordHash, Timestamp withdrawnAt) {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, password_hash, withdrawn_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, '010-0000-0000', ?, ?, NOW(6), NOW(6))", id, upid(id), email, name, passwordHash, withdrawnAt);
    }

    private void seedMember(long sellerId, long userId, String roleCode) {
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = ?", userId, sellerId, roleCode);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE actor_user_id = ?", ADMIN_ID);
                jdbc.update("DELETE FROM notification_log WHERE recipient_user_id IN (SELECT id FROM `user` WHERE email LIKE '%@89g.test')");
                jdbc.update("DELETE FROM seller_user WHERE seller_id BETWEEN 8861 AND 8864");
                jdbc.update("DELETE FROM seller WHERE id BETWEEN 8861 AND 8864");
                jdbc.update("DELETE FROM buyer_profile WHERE user_id IN (SELECT id FROM `user` WHERE email LIKE '%@89g.test')");
                jdbc.update("DELETE FROM user_role WHERE user_id IN (SELECT id FROM `user` WHERE email LIKE '%@89g.test')");
                jdbc.update("DELETE FROM `user` WHERE email LIKE '%@89g.test'");
                for (long userId : USER_IDS) {
                    jdbc.update("DELETE FROM `user` WHERE id = ?", userId);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

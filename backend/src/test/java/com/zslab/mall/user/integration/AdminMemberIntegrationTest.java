package com.zslab.mall.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.support.AbstractIntegrationTest;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * 관리자 회원 관리 API 통합 테스트(Track 84·실 MariaDB·HTTP 경유). 목록(상태·검색·lastPaidAt·등급)·상세·수정·탈퇴(가드)·임시 비밀번호
 * (응답 1회 표시·SMS 마스킹 저장·감사·로그 평문 없음·토큰 무효화·로그인 플래그·발송 실패 롤백·관리자 역할 차단·D-204)·수동 등급(AUTO 재산정
 * skip)·권한·404를 커버한다. {@link OutputCaptureExtension}으로 요청 처리 중 전체 로그에 평문이 없음을 단언한다.
 *
 * <p>{@link SmsSender}는 MockitoBean으로 대체해 발송 본문 캡처(임시 비밀번호 추출)·발송 실패 주입에 쓴다. 토큰 무효화 검증은
 * iat가 초 단위라 "발급 → 즉시 무효화"가 같은 초에 걸리면 판정이 흔들리므로, 5초 전 iat로 서명한 백데이트 토큰을 직접 만든다.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AdminMemberIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/members";
    private static final String LOGIN_URL = "/api/v1/auth/login";

    private static final long ADMIN_ID = 9840L; // JWT 액터(DB 행 불요·필터는 행 없음 통과)
    private static final long BUYER_A = 9841L;  // 활성·연락처·SILVER·배송지 1·결제 주문 1
    private static final long BUYER_B = 9842L;  // 탈퇴
    private static final long BUYER_NO_PHONE = 9843L;
    private static final long NON_BUYER = 9844L; // BUYER role 없음(판매자 계정 상정)
    private static final long BUYER_C = 9845L;  // 무관 회원(토큰 영향 없음 검증)
    private static final long BUYER_ADMIN = 9846L; // BUYER + ADMIN_OPERATOR 겸직(임시 비밀번호 발급 차단 검증·D-204)
    private static final long ORDER_A_PAID = 98411L;
    private static final long SELLER_S1 = 98461L; // (11) 활성 2명
    private static final long SELLER_S2 = 98462L; // (11) 탈퇴 구성원만
    private static final long SELLER_S3 = 98463L; // (11) soft-delete 셀러
    private static final long ORDER_A_ACTIVE = 98412L;
    private static final long ADDRESS_A = 98410L;

    private static final String BUYER_A_PID = pid("usr_", "T84BUYA");
    private static final String BUYER_B_PID = pid("usr_", "T84BUYB");
    private static final String NO_PHONE_PID = pid("usr_", "T84NOPH");
    private static final String NON_BUYER_PID = pid("usr_", "T84NONB");
    private static final String BUYER_ADMIN_PID = pid("usr_", "T84BADM");
    private static final String BUYER_A_EMAIL = "t84-buyer-a@zslab.test";
    private static final String BUYER_A_PASSWORD = "original-password-1";
    private static final int BACKDATE_MILLIS = 5_000;
    private static final long TOKEN_TTL_MILLIS = 3_600_000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private BuyerProfileRepository buyerProfileRepository;
    @Value("${jwt.secret}")
    private String jwtSecret;
    @MockitoBean
    private SmsSender smsSender;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seed();
        doNothing().when(smsSender).send(anyString(), anyString());
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ---------- 목록·상세 ----------

    @Test
    @DisplayName("(1) 목록 기본(ACTIVE) → 활성 BUYER만·탈퇴·비BUYER 제외·lastPaidAt 값/NULL·gradeCode SILVER")
    void list_default_active() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "t84-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(4)) // A·NO_PHONE·C·ADMIN 겸직(BUYER role 보유라 목록 포함)
                .andExpect(jsonPath("$.items[?(@.publicId == '" + BUYER_A_PID + "')].gradeCode").value("SILVER"))
                .andExpect(jsonPath("$.items[?(@.publicId == '" + BUYER_A_PID + "')].lastPaidAt").exists())
                .andExpect(jsonPath("$.items[?(@.publicId == '" + NO_PHONE_PID + "')].lastPaidAt").isEmpty())
                .andExpect(jsonPath("$.items[?(@.publicId == '" + BUYER_B_PID + "')]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.publicId == '" + NON_BUYER_PID + "')]").isEmpty());
    }

    @Test
    @DisplayName("(2) 목록 status=WITHDRAWN → 탈퇴 회원만·withdrawnAt 노출 / 연락처 keyword·페이지 크기·keyword 51자 400")
    void list_withdrawn_keyword_paging() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("status", "WITHDRAWN").param("keyword", "t84-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].publicId").value(BUYER_B_PID))
                .andExpect(jsonPath("$.items[0].withdrawnAt").exists());

        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "8484-00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].publicId").value(BUYER_A_PID));

        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "t84-").param("size", "1").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("keyword", "k".repeat(51)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("(3) 상세 → 등급·배송지·passwordChangeRequired false / 비BUYER·미존재 404")
    void detail() throws Exception {
        mockMvc.perform(get(URL + "/" + BUYER_A_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(BUYER_A_EMAIL))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andExpect(jsonPath("$.grade.code").value("SILVER"))
                .andExpect(jsonPath("$.grade.source").value("AUTO"))
                .andExpect(jsonPath("$.addresses.length()").value(1))
                .andExpect(jsonPath("$.addresses[0].recipientName").value("수령인A"));

        mockMvc.perform(get(URL + "/" + NON_BUYER_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(get(URL + "/" + pid("usr_", "T84NONE")).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound());
    }

    // ---------- 수정 ----------

    @Test
    @DisplayName("(4) 수정 → 204·DB 반영·감사 UPDATE USER / 탈퇴 회원 409 / phone 형식 400")
    void update() throws Exception {
        mockMvc.perform(patch(URL + "/" + BUYER_A_PID).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody("변경이름", "01099998888")))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE id = ?", String.class, BUYER_A)).isEqualTo("변경이름");
        assertThat(auditActions(BUYER_A)).containsExactly("UPDATE");
        assertThat(auditDiffs(BUYER_A).get(0)).contains("\"name\"").contains("변경이름");

        mockMvc.perform(patch(URL + "/" + BUYER_B_PID).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody("아무개", "010-0000-0000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_WITHDRAWN"));

        mockMvc.perform(patch(URL + "/" + BUYER_A_PID).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody("아무개", "02-123-4567")))
                .andExpect(status().isBadRequest());
    }

    // ---------- 탈퇴 ----------

    @Test
    @DisplayName("(5) 관리자 탈퇴: 진행 중 주문 409 → 종결 후 204·withdrawn_at·credentials_changed_at·감사 DELETE → 재탈퇴 409")
    void withdraw() throws Exception {
        seedOrder(ORDER_A_ACTIVE, BUYER_A, "SHIPPING", false);
        // Track 104-4: 결제 후 주문의 진행 판정은 품목 상태를 본다 — 주문 요약값과 맞는 품목을 함께 둔다(ORD-1)
        seedOrderItem(ORDER_A_ACTIVE, "SHIPPING");
        mockMvc.perform(post(URL + "/" + BUYER_A_PID + "/withdraw").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ACTIVITY_IN_PROGRESS"));

        jdbc.update("UPDATE `order` SET status = 'CONFIRMED' WHERE id = ?", ORDER_A_ACTIVE);
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("UPDATE order_item SET item_status = 'CONFIRMED' WHERE order_id = ?", ORDER_A_ACTIVE);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
        mockMvc.perform(post(URL + "/" + BUYER_A_PID + "/withdraw").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNoContent());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT withdrawn_at, credentials_changed_at FROM `user` WHERE id = ?", BUYER_A);
        assertThat(row.get("withdrawn_at")).isNotNull();
        assertThat(row.get("credentials_changed_at")).isNotNull();
        assertThat(auditActions(BUYER_A)).containsExactly("DELETE");

        mockMvc.perform(post(URL + "/" + BUYER_A_PID + "/withdraw").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_WITHDRAWN"));
        // 탈퇴 회원의 기존 토큰은 401(발급 시점 무관)
        mockMvc.perform(get("/api/v1/users/me").headers(authHeaders.buyer(BUYER_A)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 임시 비밀번호 ----------

    @Test
    @DisplayName("(6) 임시 비밀번호: 연락처 없음 422 / 탈퇴 409")
    void resetPassword_precondition() throws Exception {
        mockMvc.perform(post(URL + "/" + NO_PHONE_PID + "/password-reset").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MEMBER_PHONE_MISSING"));
        mockMvc.perform(post(URL + "/" + BUYER_B_PID + "/password-reset").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_WITHDRAWN"));
    }

    @ParameterizedTest(name = "(6-2) 관리자 역할 {0} 보유 회원 → 422")
    @ValueSource(strings = {"ADMIN_OPERATOR", "SUPER_ADMIN"})
    @DisplayName("(6-2) 임시 비밀번호 가드(D-204): 관리자 역할(ADMIN_OPERATOR·SUPER_ADMIN 각각) 보유 회원 → 422 MEMBER_ADMIN_ROLE_ASSIGNED·해시·플래그 불변·감사 0·SMS 0")
    void resetPassword_adminRoleHolder_rejected(String adminRoleCode) throws Exception {
        // seed는 ADMIN_OPERATOR 겸직 — SUPER_ADMIN 케이스는 역할 행을 바꿔 판정 집합(ADMIN_ROLE_CODES) 2종을 각각 실측한다(외부 검토 R1 Q7).
        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role_id IN (SELECT id FROM role WHERE code IN ('ADMIN_OPERATOR', 'SUPER_ADMIN'))", BUYER_ADMIN);
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?", BUYER_ADMIN, adminRoleCode);
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = ?", String.class, BUYER_ADMIN);

        mockMvc.perform(post(URL + "/" + BUYER_ADMIN_PID + "/password-reset").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MEMBER_ADMIN_ROLE_ASSIGNED"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT password_hash, credentials_changed_at, password_change_required FROM `user` WHERE id = ?", BUYER_ADMIN);
        assertThat(row.get("password_hash")).isEqualTo(hashBefore);
        assertThat(row.get("credentials_changed_at")).isNull();
        assertThat(row.get("password_change_required")).isEqualTo(false);
        assertThat(auditActions(BUYER_ADMIN)).isEmpty();
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("(7) 임시 비밀번호 성공(D-204): 200 + 평문 1회·no-store·SMS 원문 = 응답 평문·notification_log 마스킹·감사 displayedToActor·"
            + "감사·로그 평문 없음·이전 토큰 401·무관 회원 무영향·응답 평문 로그인 passwordChangeRequired true·이전 비밀번호 401 → "
            + "셀프 변경 204 → 새 로그인 false·변경 이전 토큰 401")
    void resetPassword_success_flow(CapturedOutput output) throws Exception {
        String tokenBeforeReset = backdatedToken(BUYER_A, ActorRole.BUYER);
        String unrelatedToken = backdatedToken(BUYER_C, ActorRole.BUYER);
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenBeforeReset))
                .andExpect(status().isOk());

        // D-204: 관리자 화면 1회 표시 — 응답 200 + 평문(D-178 §8 "응답 204·평문 없음" 대체·SMS 병행 유지)
        String resetJson = mockMvc.perform(post(URL + "/" + BUYER_A_PID + "/password-reset").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andReturn().getResponse().getContentAsString();
        String displayedPassword = objectMapper.readTree(resetJson).path("temporaryPassword").asText();
        assertThat(displayedPassword).hasSize(12).doesNotContainPattern("[0O1lI]");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(org.mockito.ArgumentMatchers.eq("010-8484-0001"), content.capture());
        String temporaryPassword = extractTemporaryPassword(content.getValue());
        assertThat(temporaryPassword).isEqualTo(displayedPassword); // SMS 병행 유지(결정 3)·같은 평문

        // 평문은 응답 본문에만(D-204) — notification_log는 마스킹본·감사·서버 로그 어디에도 없음(D-178 §8 "응답에도 없음"을 대체)
        assertThat(output.getAll()).doesNotContain(temporaryPassword);
        String storedContent = jdbc.queryForObject(
                "SELECT content FROM notification_log WHERE target_type = 'USER' AND target_id = ? AND template_code = 'TPL_TEMPORARY_PASSWORD'",
                String.class, BUYER_A);
        assertThat(storedContent).contains("****").doesNotContain(temporaryPassword);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_log WHERE target_type = 'USER' AND target_id = ?", String.class, BUYER_A))
                .isEqualTo("SENT");
        assertThat(auditActions(BUYER_A)).containsExactly("UPDATE");
        String diff = auditDiffs(BUYER_A).get(0);
        assertThat(diff).contains("\"passwordHash\"").contains("\"passwordChangeRequired\"").doesNotContain(temporaryPassword);
        assertThat(objectMapper.readTree(diff).path("displayedToActor").path("after").asBoolean()).isTrue();
        String storedHash = jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = ?", String.class, BUYER_A);
        assertThat(diff).doesNotContain(storedHash);
        assertThat(passwordEncoder.matches(temporaryPassword, storedHash)).isTrue();

        // 초기화 이전 토큰 401·무관 회원 토큰 무영향
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenBeforeReset))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + unrelatedToken))
                .andExpect(status().isOk());

        // 이전 비밀번호 401 · 응답 평문 로그인 → 플래그 true·새 토큰으로 프로필 200
        mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(BUYER_A_EMAIL, BUYER_A_PASSWORD)))
                .andExpect(status().isUnauthorized());
        String loginJson = mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(BUYER_A_EMAIL, displayedPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        String freshToken = loginJson.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + freshToken))
                .andExpect(status().isOk());

        // 셀프 변경 204(계약 유지) → 변경 이전 토큰(백데이트) 401 → 새 로그인 플래그 false.
        // 백데이트 토큰(iat -5s)이 "초기화 이후·변경 이전" 발급이 되도록 초기화 시각을 10초 앞당긴다(초 단위 경계 회피·상태 의미 무변경).
        jdbc.update("UPDATE `user` SET credentials_changed_at = credentials_changed_at - INTERVAL 10 SECOND WHERE id = ?", BUYER_A);
        String tokenBeforeChange = backdatedToken(BUYER_A, ActorRole.BUYER);
        mockMvc.perform(patch("/api/v1/users/me/password").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenBeforeChange)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + temporaryPassword + "\",\"newPassword\":\"brand-new-password-9\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenBeforeChange))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(BUYER_A_EMAIL, "brand-new-password-9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));
        assertThat(output.getAll()).doesNotContain(temporaryPassword); // 로그인·변경 로그에도 평문 없음
    }

    @Test
    @DisplayName("(8) 임시 비밀번호 SMS 실패 → 502 TEMPORARY_PASSWORD_DELIVERY_FAILED·해시 원복(롤백)·notification_log 없음")
    void resetPassword_smsFailure_rollsBack() throws Exception {
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = ?", String.class, BUYER_A);
        doThrow(new IllegalStateException("SMS 게이트웨이 오류")).when(smsSender).send(anyString(), anyString());

        mockMvc.perform(post(URL + "/" + BUYER_A_PID + "/password-reset").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TEMPORARY_PASSWORD_DELIVERY_FAILED"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT password_hash, credentials_changed_at, password_change_required FROM `user` WHERE id = ?", BUYER_A);
        assertThat(row.get("password_hash")).isEqualTo(hashBefore);
        assertThat(row.get("credentials_changed_at")).isNull();
        assertThat(row.get("password_change_required")).isEqualTo(false); // TINYINT(1) → Boolean
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE target_type = 'USER' AND target_id = ?", Long.class, BUYER_A))
                .isZero();
        assertThat(auditActions(BUYER_A)).isEmpty();
        mockMvc.perform(get("/api/v1/users/me").headers(authHeaders.buyer(BUYER_A))).andExpect(status().isOk());
    }

    // ---------- 등급 ----------

    @Test
    @DisplayName("(9) 수동 등급: PUT → 204·MANUAL·locked_until·감사 / lockedUntil 과거 400 / 이후 AUTO 재산정 skip(등급 유지)")
    void changeGrade() throws Exception {
        LocalDate lockedUntil = LocalDate.now().plusDays(7);
        mockMvc.perform(put(URL + "/" + BUYER_A_PID + "/grade").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gradeCode\":\"PLATINUM\",\"lockedUntil\":\"" + lockedUntil + "\"}"))
                .andExpect(status().isNoContent());
        Map<String, Object> profile = jdbc.queryForMap(
                "SELECT grade_id, grade_source, grade_locked_until FROM buyer_profile WHERE user_id = ?", BUYER_A);
        assertThat(profile.get("grade_id")).isEqualTo(gradeId("PLATINUM"));
        assertThat(profile.get("grade_source")).isEqualTo("MANUAL");
        // 외부 검토 반영: DATETIME(6) 마이크로초 정밀도로 지정일 23:59:59.999999에 고정·익일로 넘어가지 않음(DB 직접 조회 + 영속성 컨텍스트 clear 후 JPA 재조회)
        LocalDateTime expectedLockedUntil = lockedUntil.atTime(23, 59, 59, 999_999_000);
        assertThat(((Timestamp) profile.get("grade_locked_until")).toLocalDateTime()).isEqualTo(expectedLockedUntil);
        LocalDateTime reloaded = tx.execute(s -> {
            entityManager.clear();
            return buyerProfileRepository.findById(BUYER_A).orElseThrow().getGradeLockedUntil();
        });
        assertThat(reloaded).isEqualTo(expectedLockedUntil);
        assertThat(reloaded.toLocalDate()).isEqualTo(lockedUntil);
        assertThat(auditActions(BUYER_A)).containsExactly("UPDATE");
        assertThat(auditDiffs(BUYER_A).get(0)).contains("\"gradeId\"").contains("MANUAL");

        mockMvc.perform(put(URL + "/" + BUYER_A_PID + "/grade").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gradeCode\":\"GOLD\",\"lockedUntil\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(URL + "/" + BUYER_A_PID + "/grade").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gradeCode\":\"DIAMOND\",\"lockedUntil\":\"" + lockedUntil + "\"}"))
                .andExpect(status().isBadRequest());

        // lock 기간 중 AUTO 재산정(lifetime 0 → SILVER 구간)은 skip → PLATINUM 유지
        mockMvc.perform(post("/api/v1/admin/buyers/" + BUYER_A_PID + "/grade/recalculate").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT grade_id FROM buyer_profile WHERE user_id = ?", Long.class, BUYER_A))
                .isEqualTo(gradeId("PLATINUM"));
    }

    // ---------- 권한 ----------

    @Test
    @DisplayName("(10) BUYER·SELLER 토큰 → 403 / 미인증 401")
    void authorization() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_A))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).headers(authHeaders.seller(NON_BUYER))).andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/" + BUYER_A_PID + "/withdraw").headers(authHeaders.buyer(BUYER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }


    // ---------- 셀러 소속(Track 89-G STEP 498·D-189) ----------

    @Test
    @DisplayName("(11) 상세 sellerMembership: 활성 2명 중 1명 false → 유일 활성 true / 탈퇴 구성원은 필드 있음·false / 미소속·soft-delete 셀러는 키 없음 / 목록엔 키 없음 / 키 집합")
    void detailSellerMembership() throws Exception {
        // 셀러 S1: BUYER_A(OWNER·활성) + BUYER_C(STAFF·활성) / 셀러 S2: BUYER_B(OWNER·탈퇴)만 / 셀러 S3(soft-delete): BUYER_NO_PHONE(STAFF)
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedSeller(SELLER_S1, "T84셀러S1", null);
                seedSeller(SELLER_S2, "T84셀러S2", null);
                seedSeller(SELLER_S3, "T84셀러S3", Timestamp.valueOf("2026-09-01 00:00:00"));
                seedSellerUser(SELLER_S1, BUYER_A, "SELLER_OWNER");
                seedSellerUser(SELLER_S1, BUYER_C, "SELLER_STAFF");
                seedSellerUser(SELLER_S2, BUYER_B, "SELLER_OWNER");
                seedSellerUser(SELLER_S3, BUYER_NO_PHONE, "SELLER_STAFF");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });

        // 활성 2명 중 1명 → false·상호·역할
        String body = mockMvc.perform(get(URL + "/" + BUYER_A_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerMembership.sellerPublicId").value(pid("slr_", "T84S1")))
                .andExpect(jsonPath("$.sellerMembership.companyName").value("T84셀러S1"))
                .andExpect(jsonPath("$.sellerMembership.roleCode").value("SELLER_OWNER"))
                .andExpect(jsonPath("$.sellerMembership.lastActiveMember").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode detail = objectMapper.readTree(body);
        assertThat(fieldNames(detail)).containsExactlyInAnyOrder(
                "publicId", "name", "email", "phone", "createdAt", "passwordChangeRequired", "grade", "addresses", "sellerMembership");
        assertThat(fieldNames(detail.get("sellerMembership"))).containsExactlyInAnyOrder("sellerPublicId", "companyName", "roleCode", "lastActiveMember");

        // 다른 활성 구성원(BUYER_C)이 탈퇴 → BUYER_A가 유일 활성 → true(역할 무관·STAFF가 빠져도 판정에 반영)
        jdbc.update("UPDATE `user` SET withdrawn_at = NOW(6) WHERE id = ?", BUYER_C);
        mockMvc.perform(get(URL + "/" + BUYER_A_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerMembership.lastActiveMember").value(true));
        // 탈퇴한 구성원(BUYER_C·BUYER_B): 소속 필드는 있고(행 유지·D-187 §1-A 11) lastActiveMember false
        mockMvc.perform(get(URL + "/" + pid("usr_", "T84BUYC")).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerMembership.roleCode").value("SELLER_STAFF"))
                .andExpect(jsonPath("$.sellerMembership.lastActiveMember").value(false));
        mockMvc.perform(get(URL + "/" + BUYER_B_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerMembership.companyName").value("T84셀러S2"))
                .andExpect(jsonPath("$.sellerMembership.lastActiveMember").value(false));
        // 미소속·soft-delete 셀러 구성원 → 키 없음
        mockMvc.perform(get(URL + "/" + NO_PHONE_PID).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerMembership").doesNotExist());
        // 목록엔 싣지 않는다
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].sellerMembership").doesNotExist());
        // 탈퇴는 여전히 차단하지 않는다(확정 4): 유일 활성 구성원 BUYER_A 탈퇴 → 204(진행 중 주문 없음·ORDER_A_PAID는 CONFIRMED)
        mockMvc.perform(post(URL + "/" + BUYER_A_PID + "/withdraw").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNoContent());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void seedSeller(long id, String companyName, Timestamp deletedAt) {
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, deleted_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', 'ACTIVE', ?, NOW(6), NOW(6))", id, pid("slr_", "T84S" + (id - SELLER_S1 + 1)), companyName, deletedAt);
    }

    private void seedSellerUser(long sellerId, long userId, String roleCode) {
        jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = ?", userId, sellerId, roleCode);
    }
    // ---------- seed·helpers (? positional 바인딩·정적 SQL·SQL injection 없음) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedUser(BUYER_A, BUYER_A_PID, BUYER_A_EMAIL, "구매자A", "010-8484-0001", BUYER_A_PASSWORD, false, true);
                seedUser(BUYER_B, BUYER_B_PID, "t84-buyer-b@zslab.test", "구매자B", "010-2222-3333", "pw-b-0000000", true, true);
                seedUser(BUYER_NO_PHONE, NO_PHONE_PID, "t84-nophone@zslab.test", "무연락처", null, "pw-n-0000000", false, true);
                seedUser(NON_BUYER, NON_BUYER_PID, "t84-nonbuyer@zslab.test", "판매자계정", "010-7777-8888", "pw-s-0000000", false, false);
                seedUser(BUYER_C, pid("usr_", "T84BUYC"), "t84-buyer-c@zslab.test", "구매자C", "010-5555-6666", "pw-c-0000000", false, true);
                seedUser(BUYER_ADMIN, BUYER_ADMIN_PID, "t84-buyer-admin@zslab.test", "운영자겸직", "010-9999-0000", "pw-a-0000000", false, true);
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'ADMIN_OPERATOR'", BUYER_ADMIN);
                jdbc.update("INSERT INTO user_address (id, user_id, is_default, recipient_name, recipient_phone, zonecode, address_road, "
                                + "created_at, updated_at) VALUES (?, ?, 1, '수령인A', '010-1234-5678', '06236', '서울 강남구 테헤란로 1', NOW(6), NOW(6))",
                        ADDRESS_A, BUYER_A);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
        seedOrder(ORDER_A_PAID, BUYER_A, "CONFIRMED", true);
    }

    private void seedUser(long id, String publicId, String email, String name, String phone, String password,
            boolean withdrawn, boolean buyerRole) {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, phone, password_hash, withdrawn_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, publicId, email, name, phone, passwordEncoder.encode(password), withdrawn ? Timestamp.valueOf("2026-09-01 00:00:00") : null);
        if (buyerRole) {
            jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'BUYER'", id);
            jdbc.update("INSERT INTO buyer_profile (user_id, grade_id, grade_source, created_at, updated_at) "
                    + "VALUES (?, (SELECT id FROM buyer_grade WHERE code = 'SILVER'), 'AUTO', NOW(6), NOW(6))", id);
        }
    }

    private void seedOrder(long orderId, long buyerId, String status, boolean paid) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                                + "ordered_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 10000, 0, 0, NOW(6), ?, NOW(6), NOW(6))",
                        orderId, pid("ord_", "T84O" + orderId), buyerId, "ORDT84" + orderId, status,
                        paid ? Timestamp.valueOf("2026-09-10 12:00:00") : null);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /** 주문 1건에 품목 1건(id = 주문 id·FK_CHECKS=0으로 상품·셀러 없이 심는다). */
    private void seedOrderItem(long orderId, String itemStatus) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                                + "total_price, item_status, product_name, created_at, updated_at, commission_rate) "
                                + "VALUES (?, ?, ?, 1, 1, 1, 1, 10000, 10000, ?, '탈퇴가드상품', NOW(6), NOW(6), 1000)",
                        orderId, pid("oit_", "T84I" + orderId), orderId, itemStatus);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM seller_user WHERE seller_id IN (?, ?, ?)", SELLER_S1, SELLER_S2, SELLER_S3);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?, ?)", SELLER_S1, SELLER_S2, SELLER_S3);
                List<Long> ids = List.of(BUYER_A, BUYER_B, BUYER_NO_PHONE, NON_BUYER, BUYER_C, BUYER_ADMIN);
                for (Long id : ids) {
                    jdbc.update("DELETE FROM audit_log WHERE target_type = 'USER' AND target_id = ?", id);
                    jdbc.update("DELETE FROM notification_log WHERE target_type = 'USER' AND target_id = ?", id);
                    jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", id);
                    jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", id);
                    jdbc.update("DELETE FROM user_address WHERE user_id = ?", id);
                    jdbc.update("DELETE FROM buyer_profile WHERE user_id = ?", id);
                    jdbc.update("DELETE FROM user_role WHERE user_id = ?", id);
                    jdbc.update("DELETE FROM `user` WHERE id = ?", id);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private List<String> auditActions(long targetId) {
        return jdbc.queryForList("SELECT action FROM audit_log WHERE target_type = 'USER' AND target_id = ? ORDER BY id",
                String.class, targetId);
    }

    private List<String> auditDiffs(long targetId) {
        return jdbc.queryForList("SELECT diff_json FROM audit_log WHERE target_type = 'USER' AND target_id = ? ORDER BY id",
                String.class, targetId);
    }

    private long gradeId(String code) {
        return jdbc.queryForObject("SELECT id FROM buyer_grade WHERE code = ?", Long.class, code);
    }

    /** iat를 {@value #BACKDATE_MILLIS}ms 앞당긴 서명 토큰 — "무효화 시각 이전 발급"을 초 단위 경계와 무관하게 재현한다. */
    private String backdatedToken(long userId, ActorRole role) {
        long issued = System.currentTimeMillis() - BACKDATE_MILLIS;
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role.name())
                .issuedAt(new Date(issued))
                .expiration(new Date(issued + TOKEN_TTL_MILLIS))
                .signWith(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256)
                .compact();
    }

    /** SMS 본문 "[zslab-mall] 임시 비밀번호: {pw} 로그인 후 ..."에서 평문을 추출한다(테스트 전용). */
    private static String extractTemporaryPassword(String smsContent) {
        String marker = "임시 비밀번호: ";
        int start = smsContent.indexOf(marker) + marker.length();
        return smsContent.substring(start, smsContent.indexOf(' ', start));
    }

    private static String updateBody(String name, String phone) {
        return "{\"name\":\"" + name + "\",\"phone\":\"" + phone + "\"}";
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"BUYER\"}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

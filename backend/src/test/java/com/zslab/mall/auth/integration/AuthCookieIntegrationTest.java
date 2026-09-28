package com.zslab.mall.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 역할별 HttpOnly 쿠키 인증(D-235·통합 검토 3번 PR1) 통합 테스트. 역할 로그인의 쿠키 발급·경로 접두사별 쿠키 선택·헤더 우선 불변식·
 * CSRF(쿠키가 자격증명일 때만)·역할별 로그아웃을 실 SecurityFilterChain으로 검증한다. 쿠키 이름은 서버 계약 그 자체라 문자열로 고정한다.
 */
@AutoConfigureMockMvc
class AuthCookieIntegrationTest extends AbstractIntegrationTest {

    private static final long BUYER_ID = 13510L;
    private static final long SELLER_USER_ID = 13511L;
    private static final long SELLER_ID = 13512L;
    private static final long ADMIN_ID = 13513L;
    private static final String BUYER_EMAIL = "cookie-buyer@zslab.test";
    private static final String SELLER_EMAIL = "cookie-seller@zslab.test";
    private static final String ADMIN_EMAIL = "cookie-admin@zslab.test";
    private static final String SELLER_NAME = "쿠키셀러";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String BUYER_COOKIE = "__Secure-buyer_at";
    private static final String SELLER_COOKIE = "__Secure-seller_at";
    private static final String ADMIN_COOKIE = "__Secure-admin_at";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String INVALID_TOKEN = "not.a.jwt";
    private static final long MILLIS_PER_SECOND = 1000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TokenProvider tokenProvider;
    @Autowired
    private AuthHeaders authHeaders;
    @Value("${jwt.expiration-ms}")
    private long expirationMs;

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

    // ---------- 역할 로그인·쿠키 발급 ----------

    @Test
    @DisplayName("역할 로그인 3종: 구매자·셀러·관리자 각각 자기 쿠키 1건만 HttpOnly·Secure·SameSite=Lax·Max-Age=TTL·역할 Path로 발급 + 본문 token 유지")
    void roleLogins_issueOwnCookieOnly() throws Exception {
        assertIssuedCookie(login("/api/v1/auth/buyer/login", BUYER_EMAIL, null), BUYER_COOKIE, "/"); // D-235 개정 1: 구매자 Path "/"
        assertIssuedCookie(login("/api/v1/seller/auth/login", SELLER_EMAIL, null), SELLER_COOKIE, "/api/v1/seller");
        assertIssuedCookie(login("/api/v1/admin/auth/login", ADMIN_EMAIL, null), ADMIN_COOKIE, "/api/v1/admin");
    }

    @Test
    @DisplayName("역할이 없는 계정의 셀러·관리자 로그인 → 401 AUTHENTICATION_FAILED·쿠키 없음")
    void roleLogin_withoutRole_fails() throws Exception {
        for (String url : List.of("/api/v1/seller/auth/login", "/api/v1/admin/auth/login")) {
            MvcResult result = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(BUYER_EMAIL, null)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                    .andReturn();
            assertThat(authCookieHeaders(result)).isEmpty();
        }
    }

    @Test
    @DisplayName("기존 /api/v1/auth/login은 BUYER·SELLER·ADMIN 모든 role에서 200·본문 token이지만 쿠키는 발급하지 않는다(확장-수축)")
    void legacyLogin_anyRole_issuesNoCookie() throws Exception {
        String[][] accounts = {{BUYER_EMAIL, "BUYER"}, {SELLER_EMAIL, "SELLER"}, {ADMIN_EMAIL, "ADMIN"}};
        for (String[] account : accounts) {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(account[0], account[1])))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.token").exists()).andReturn();
            assertThat(authCookieHeaders(result)).isEmpty();
        }
    }

    // ---------- 경로 접두사별 쿠키 선택(D2) ----------

    @Test
    @DisplayName("쿠키만 있는 GET: 자기 접두사에서 인증 — /users/me(구매자)·/seller/me(셀러·name·email 포함)·/admin/me(관리자)")
    void cookieOnlyGet_authenticatesOnOwnPrefix() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").cookie(buyerCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(BUYER_EMAIL));
        mockMvc.perform(get("/api/v1/seller/me").cookie(sellerCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(SELLER_NAME))
                .andExpect(jsonPath("$.email").value(SELLER_EMAIL));
        mockMvc.perform(get("/api/v1/admin/me").cookie(adminCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(ADMIN_EMAIL));
    }

    @Test
    @DisplayName("세 역할 쿠키 동시: /admin/** → 관리자 · /seller/** → 셀러 · 그 외 /api/v1/** → 구매자로 인증")
    void allThreeCookies_pathPrefixSelectsOne() throws Exception {
        Cookie[] all = {buyerCookie(), sellerCookie(), adminCookie()};
        mockMvc.perform(get("/api/v1/admin/me").cookie(all))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(ADMIN_EMAIL));
        mockMvc.perform(get("/api/v1/seller/me").cookie(all))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(SELLER_EMAIL));
        mockMvc.perform(get("/api/v1/users/me").cookie(all))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(BUYER_EMAIL));
    }

    @Test
    @DisplayName("다른 역할 쿠키로는 인증하지 않는다: 구매자 쿠키 → /admin/** 401 · 관리자 쿠키 → /seller/** 401 · 셀러 쿠키 → /users/me 401")
    void otherRoleCookie_isNotUsed() throws Exception {
        expectUnauthenticated(get("/api/v1/admin/me").cookie(buyerCookie()));
        expectUnauthenticated(get("/api/v1/seller/me").cookie(adminCookie()));
        expectUnauthenticated(get("/api/v1/users/me").cookie(sellerCookie()));
    }

    @Test
    @DisplayName("관리자 쿠키 무효 + 구매자 쿠키 유효 → /admin/** 401(구매자로 대체 인증하지 않음)")
    void invalidAdminCookie_doesNotFallBackToBuyer() throws Exception {
        expectUnauthenticated(get("/api/v1/admin/me")
                .cookie(new Cookie(ADMIN_COOKIE, INVALID_TOKEN), buyerCookie()));
    }

    @Test
    @DisplayName("쿠키 이름과 토큰 역할이 다르면 인증하지 않는다: 관리자 쿠키 자리에 구매자 토큰 → /admin/** 401")
    void cookieRoleMismatch_isNotAuthenticated() throws Exception {
        expectUnauthenticated(get("/api/v1/admin/me")
                .cookie(new Cookie(ADMIN_COOKIE, tokenProvider.issue(BUYER_ID, ActorRole.BUYER))));
    }

    @Test
    @DisplayName("Authorization 헤더가 무효면 유효한 역할 쿠키가 있어도 401(쿠키로 대체 인증하지 않음)")
    void invalidBearer_withValidCookie_isUnauthenticated() throws Exception {
        expectUnauthenticated(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + INVALID_TOKEN).cookie(buyerCookie()));
    }

    @Test
    @DisplayName("비-Bearer·빈 Authorization 헤더 + 유효 역할 쿠키 → GET 401 · CSRF 없는 unsafe도 403이 아니라 401(헤더가 있으면 쿠키 무시·CSRF 비적용)")
    void nonBearerOrEmptyHeader_withValidCookie_isUnauthenticated() throws Exception {
        for (String headerValue : List.of("Basic dXNlcjpwYXNz", "")) {
            expectUnauthenticated(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, headerValue).cookie(buyerCookie()));
            expectUnauthenticated(patch("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, headerValue).cookie(buyerCookie())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"헤더\",\"phone\":\"010-3333-4444\"}"));
        }
    }

    @Test
    @DisplayName("유효 Bearer + 역할 쿠키 동시 · CSRF 토큰 없는 unsafe → Bearer로 성공(쿠키가 있어도 헤더 요청은 CSRF 면제)")
    void bearerWithCookie_unsafe_isExemptFromCsrf() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").headers(authHeaders.buyer(BUYER_ID)).cookie(buyerCookie(), adminCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"동시\",\"phone\":\"010-5555-6666\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("동시"));
    }

    @Test
    @DisplayName("옛 이름 쿠키(auth_token)만 있는 unsafe 요청 → 401(CSRF 403 아님·옛 이름은 자격증명이 아니다)")
    void legacyCookieName_unsafe_isUnauthenticated() throws Exception {
        expectUnauthenticated(patch("/api/v1/users/me")
                .cookie(new Cookie("auth_token", tokenProvider.issue(BUYER_ID, ActorRole.BUYER)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"옛쿠키\",\"phone\":\"010-7777-8888\"}"));
    }

    @Test
    @DisplayName("옛 이름 쿠키(auth_token·seller_token·admin_token)만 있으면 새 규칙으로 인증되지 않는다")
    void legacyCookieNames_areNotRecognized() throws Exception {
        expectUnauthenticated(get("/api/v1/users/me")
                .cookie(new Cookie("auth_token", tokenProvider.issue(BUYER_ID, ActorRole.BUYER))));
        expectUnauthenticated(get("/api/v1/seller/me")
                .cookie(new Cookie("seller_token", tokenProvider.issue(SELLER_USER_ID, ActorRole.SELLER))));
        expectUnauthenticated(get("/api/v1/admin/me")
                .cookie(new Cookie("admin_token", tokenProvider.issue(ADMIN_ID, ActorRole.ADMIN))));
    }

    // ---------- D0 셀러 별칭(클레임 첨부 별칭은 ClaimAttachmentServingIntegrationTest) ----------

    @Test
    @DisplayName("셀러 별칭 prepare-shipment·mark-delivered: 셀러 쿠키+CSRF → 원 핸들러 도달(미존재 publicId 404 ORDER_NOT_FOUND·DELIVERY_NOT_FOUND) / 구매자·관리자 쿠키 → 401")
    void sellerShippingAliases_sellerCookieOnly() throws Exception {
        String csrf = fetchCsrfToken();
        String prepareUrl = "/api/v1/seller/order-items/oit_NOTEXIST000000000000000000/prepare-shipment";
        String prepareBody = "{\"carrier\":\"CJ\",\"trackingNo\":\"123456789012\"}";
        String deliveredUrl = "/api/v1/seller/deliveries/dlv_NOTEXIST000000000000000000/mark-delivered";
        // 핸들러 고유 404 코드로 "경로 없음 404"가 아니라 원 핸들러의 publicId 게이트에 도달했음을 확인한다.
        mockMvc.perform(post(prepareUrl).cookie(sellerCookie(), new Cookie(CSRF_COOKIE, csrf)).header(CSRF_HEADER, csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(prepareBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(post(deliveredUrl).cookie(sellerCookie(), new Cookie(CSRF_COOKIE, csrf)).header(CSRF_HEADER, csrf))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
        for (Cookie other : List.of(buyerCookie(), adminCookie())) {
            expectUnauthenticated(post(prepareUrl).cookie(other, new Cookie(CSRF_COOKIE, csrf)).header(CSRF_HEADER, csrf)
                    .contentType(MediaType.APPLICATION_JSON).content(prepareBody));
            expectUnauthenticated(post(deliveredUrl).cookie(other, new Cookie(CSRF_COOKIE, csrf)).header(CSRF_HEADER, csrf));
        }
    }

    @Test
    @DisplayName("셀러 별칭 PATCH /seller/me/password: 셀러 쿠키+CSRF → 204 / 구매자·관리자 쿠키 → 401 / 구매자 Bearer → 403")
    void sellerPasswordAlias_sellerOnly() throws Exception {
        String csrf = fetchCsrfToken();
        String url = "/api/v1/seller/me/password";
        String body = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another-horse-battery\"}";
        for (Cookie other : List.of(buyerCookie(), adminCookie())) {
            expectUnauthenticated(patch(url).cookie(other, new Cookie(CSRF_COOKIE, csrf)).header(CSRF_HEADER, csrf)
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }
        mockMvc.perform(patch(url).headers(authHeaders.buyer(BUYER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(url).cookie(sellerCookie(), new Cookie(CSRF_COOKIE, csrf)).header(CSRF_HEADER, csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("SUSPENDED 셀러도 셀러 쿠키+CSRF로 PATCH /seller/me/password 204(원 경로와 같이 상태 가드 없음·D-235 외부 검토 반영)")
    void sellerPasswordAlias_suspendedSeller_allowed() throws Exception {
        jdbc.update("UPDATE seller SET status = 'SUSPENDED' WHERE id = ?", SELLER_ID);
        String csrf = fetchCsrfToken();
        mockMvc.perform(patch("/api/v1/seller/me/password").cookie(sellerCookie(), new Cookie(CSRF_COOKIE, csrf))
                        .header(CSRF_HEADER, csrf).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"suspended-horse-battery\"}"))
                .andExpect(status().isNoContent());
    }

    // ---------- 경로 경계: 쿠키 선택 역할 = 인가 요구 역할(R1-3) ----------

    /**
     * 경계 경로마다 역할 쿠키를 하나씩 보내 "인가를 통과(401·403 아님)하는 쿠키 역할"이 인가가 요구하는 역할과 정확히 같은지 본다.
     * 방화벽이 요청 자체를 거부(400)하면 어떤 쿠키로도 인증 단계에 닿지 않으므로 불일치 없음으로 본다.
     * raw 경로는 MockMvc가 다시 인코딩하지 않도록 URI 객체로 보낸다.
     */
    @Test
    @DisplayName("경로 경계(끝 슬래시·빈 세그먼트·세미콜론·인코딩): 쿠키 선택 역할과 인가 요구 역할이 일치하거나 프레임워크가 400으로 거부")
    void pathBoundaries_cookieSelectionMatchesAuthorization() throws Exception {
        String[][] cases = {
                {"/api/v1/admin", "ADMIN"},
                {"/api/v1/admin/", "ADMIN"},
                {"/api/v1/admin//me", "REJECTED"},
                {"/api/v1/admin;x/me", "REJECTED"},
                {"/api/v1/%61dmin/me", "ADMIN"},
                {"/api/v1/seller;x/me", "REJECTED"},
                {"/api/v1/seller/me/", "SELLER"}};
        for (String[] boundary : cases) {
            URI uri = URI.create(boundary[0]);
            int buyerOnly = statusOf(get(uri).cookie(buyerCookie()));
            int sellerOnly = statusOf(get(uri).cookie(sellerCookie()));
            int adminOnly = statusOf(get(uri).cookie(adminCookie()));
            int all = statusOf(get(uri).cookie(buyerCookie(), sellerCookie(), adminCookie()));
            String observed = boundary[0] + " buyer=" + buyerOnly + " seller=" + sellerOnly + " admin=" + adminOnly + " all=" + all;
            if (boundary[1].equals("REJECTED")) {
                assertThat(List.of(buyerOnly, sellerOnly, adminOnly, all)).as(observed).containsOnly(400);
                continue;
            }
            boolean adminPath = boundary[1].equals("ADMIN");
            assertThat(buyerOnly).as(observed).isEqualTo(401);
            assertThat(adminPath ? sellerOnly : adminOnly).as(observed).isEqualTo(401);
            assertThat(adminPath ? adminOnly : sellerOnly).as(observed).isNotIn(401, 403);
            assertThat(all).as(observed).isEqualTo(adminPath ? adminOnly : sellerOnly);
        }
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    // ---------- CSRF(D3·Q1) ----------

    @Test
    @DisplayName("쿠키 인증 unsafe 요청: CSRF 토큰 없으면 403 FORBIDDEN · 토큰(쿠키+헤더) 있으면 성공")
    void cookieAuthUnsafe_requiresCsrfToken() throws Exception {
        String body = "{\"name\":\"새이름\",\"phone\":\"010-1234-5678\"}";
        mockMvc.perform(patch("/api/v1/users/me").cookie(buyerCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        String csrf = fetchCsrfToken();
        mockMvc.perform(patch("/api/v1/users/me").cookie(buyerCookie(), new Cookie(CSRF_COOKIE, csrf))
                        .header(CSRF_HEADER, csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새이름"));
    }

    @Test
    @DisplayName("Bearer unsafe 요청은 CSRF 토큰 없이 성공(호환)")
    void bearerUnsafe_isExemptFromCsrf() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"베어러\",\"phone\":\"010-1111-2222\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("쿠키·헤더 모두 없는 익명 unsafe 요청은 기존대로 401 UNAUTHENTICATED(CSRF 403 아님)")
    void anonymousUnsafe_staysUnauthenticated() throws Exception {
        expectUnauthenticated(post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    @DisplayName("웹훅 POST·가입 POST는 CSRF 토큰 없이 기존과 동일하게 동작(쿠키 없음 → CSRF 비적용)")
    void webhookAndSignup_workWithoutCsrf() throws Exception {
        // 입력·기대는 PaymentWebhookIntegrationTest.webhook_pgTidTooLong_rejects400(:202-208)과 같다 — pgTid 101자 → Bean Validation 400
        // (서비스 진입 전 차단이라 시드 불필요). CSRF가 끼면 403이 되므로 400이면 CSRF 비적용이 확인된다.
        String tooLongPgTid = "t".repeat(101);
        mockMvc.perform(post("/api/webhooks/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"MOCK_PG\",\"callbackType\": \"SUCCESS\",\"paymentAttemptKey\": \"pat_track6_it_0001\","
                                + "\"pgTid\": \"" + tooLongPgTid + "\",\"occurredAt\": \"2026-06-28T00:00:00\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cookie-signup@zslab.test\",\"name\":\"가입\",\"phone\":\"010-2222-3333\","
                                + "\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("로그인은 CSRF 면제: 기존 역할 쿠키가 있어도 토큰 없이 로그인 성공")
    void login_isExemptFromCsrf_evenWithCookie() throws Exception {
        mockMvc.perform(post("/api/v1/admin/auth/login").cookie(adminCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(ADMIN_EMAIL, null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/buyer/login").cookie(buyerCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(BUYER_EMAIL, null)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("역할 로그인 3종 성공 응답(CSRF 헤더·쿠키 없이 200)에 XSRF-TOKEN Set-Cookie가 있고 속성은 전역 발급(공개 GET)과 같다(D-235 개정 2)")
    void roleLogins_issueXsrfTokenCookie() throws Exception {
        String globalAttributes = csrfCookieAttributes(mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk()).andReturn());
        assertThat(globalAttributes).isNotNull();
        String[][] logins = {{"/api/v1/auth/buyer/login", BUYER_EMAIL}, {"/api/v1/seller/auth/login", SELLER_EMAIL},
                {"/api/v1/admin/auth/login", ADMIN_EMAIL}};
        for (String[] roleLogin : logins) {
            assertThat(csrfCookieAttributes(login(roleLogin[0], roleLogin[1], null))).as(roleLogin[0]).isEqualTo(globalAttributes);
        }
    }

    @Test
    @DisplayName("구매자 쿠키 로그인은 BUYER 역할이 없는 계정(셀러 전용)이면 401·쿠키 없음")
    void buyerLogin_withoutBuyerRole_fails() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/buyer/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(SELLER_EMAIL, null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andReturn();
        assertThat(authCookieHeaders(result)).isEmpty();
    }

    // ---------- 로그아웃(D4) ----------

    @Test
    @DisplayName("로그아웃: 쿠키 인증 + CSRF 토큰 없음 → 403 / 토큰 있음 → 204·자기 역할 쿠키만 Max-Age=0")
    void logout_expiresOwnCookieOnly_andRequiresCsrf() throws Exception {
        List<String[]> roles = List.of(
                new String[] {"/api/v1/auth/logout", BUYER_COOKIE, "/"}, // D-235 개정 1: 발급과 같은 Path여야 만료된다
                new String[] {"/api/v1/seller/auth/logout", SELLER_COOKIE, "/api/v1/seller"},
                new String[] {"/api/v1/admin/auth/logout", ADMIN_COOKIE, "/api/v1/admin"});
        String csrf = fetchCsrfToken();
        for (String[] role : roles) {
            Cookie[] all = {buyerCookie(), sellerCookie(), adminCookie()};
            mockMvc.perform(post(role[0]).cookie(all))
                    .andExpect(status().isForbidden());
            MvcResult result = mockMvc.perform(post(role[0]).cookie(all).cookie(new Cookie(CSRF_COOKIE, csrf))
                            .header(CSRF_HEADER, csrf))
                    .andExpect(status().isNoContent())
                    .andReturn();
            List<String> expired = authCookieHeaders(result);
            assertThat(expired).hasSize(1);
            assertThat(expired.get(0)).startsWith(role[1] + "=")
                    .contains("Max-Age=0", "Path=" + role[2], "HttpOnly", "Secure", "SameSite=Lax");
        }
    }

    @Test
    @DisplayName("프레임워크 기본 /logout 비활성(S6): GET·POST /logout은 역할 쿠키를 만료시키지 않는다")
    void defaultLogout_isDisabled() throws Exception {
        Cookie[] all = {buyerCookie(), sellerCookie(), adminCookie()};
        for (MockHttpServletRequestBuilder request : List.of(get("/logout").cookie(all), post("/logout").cookie(all))) {
            MvcResult result = mockMvc.perform(request).andReturn();
            assertThat(authCookieHeaders(result)).isEmpty();
            assertThat(result.getResponse().getStatus()).isNotIn(204, 302);
        }
    }

    // ---------- helpers ----------

    private MvcResult login(String url, String email, String role) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(loginBody(email, role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andReturn();
    }

    private void assertIssuedCookie(MvcResult result, String name, String path) {
        List<String> cookies = authCookieHeaders(result);
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).startsWith(name + "=")
                .contains("Path=" + path, "Max-Age=" + expirationMs / MILLIS_PER_SECOND, "HttpOnly", "Secure",
                        "SameSite=Lax");
    }

    /** 응답 Set-Cookie 중 역할 인증 쿠키(새 이름·옛 이름)만. CSRF 쿠키는 제외한다. */
    private static List<String> authCookieHeaders(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> !header.startsWith(CSRF_COOKIE + "="))
                .toList();
    }

    /** 응답의 XSRF-TOKEN Set-Cookie에서 값을 뺀 속성 부분(없으면 null). 중복 발급이 없어야 하므로 2건 이상이면 실패한다. */
    private static String csrfCookieAttributes(MvcResult result) {
        List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(CSRF_COOKIE + "="))
                .toList();
        assertThat(csrfHeaders).hasSizeLessThanOrEqualTo(1);
        return csrfHeaders.isEmpty() ? null : csrfHeaders.get(0).replaceFirst("^" + CSRF_COOKIE + "=[^;]*", "");
    }

    /** 공개 GET으로 CSRF 쿠키를 받는다(SPA 흐름: 서버가 XSRF-TOKEN을 내려주고 클라이언트가 헤더로 되돌려 보낸다). */
    private String fetchCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/categories")).andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private void expectUnauthenticated(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private Cookie buyerCookie() {
        return new Cookie(BUYER_COOKIE, tokenProvider.issue(BUYER_ID, ActorRole.BUYER));
    }

    private Cookie sellerCookie() {
        return new Cookie(SELLER_COOKIE, tokenProvider.issue(SELLER_USER_ID, ActorRole.SELLER));
    }

    private Cookie adminCookie() {
        return new Cookie(ADMIN_COOKIE, tokenProvider.issue(ADMIN_ID, ActorRole.ADMIN));
    }

    private static String loginBody(String email, String role) {
        String roleField = role == null ? "" : ",\"role\":\"" + role + "\"";
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"" + roleField + "}";
    }

    // ---------- seed (? positional 바인딩·SQL injection 없음) ----------

    private void seed() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                insertUser(BUYER_ID, "CKBUYER", BUYER_EMAIL, "쿠키구매자");
                insertUser(SELLER_USER_ID, "CKSELLER", SELLER_EMAIL, SELLER_NAME);
                insertUser(ADMIN_ID, "CKADMIN", ADMIN_EMAIL, "쿠키관리자");
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                        BUYER_ID, "BUYER");
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                        ADMIN_ID, "ADMIN_OPERATOR");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "CKSELLER"), "쿠키상점", "대표");
                jdbc.update("INSERT INTO seller_user (seller_id, user_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = ?",
                        SELLER_ID, SELLER_USER_ID, "SELLER_OWNER");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertUser(long id, String tag, String email, String name) {
        jdbc.update("INSERT INTO `user` (id, public_id, email, name, password_hash, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("usr_", tag), email, name, passwordEncoder.encode(PASSWORD));
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?)", BUYER_ID, SELLER_USER_ID, ADMIN_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", SELLER_USER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                // 가입 경로는 user_role·buyer_profile을 함께 만든다(UserService.register) — 남기면 buyer 집계 테스트(AdminGrade 등)가 오염된다.
                jdbc.update("DELETE FROM user_role WHERE user_id IN (SELECT id FROM `user` WHERE email = ?)",
                        "cookie-signup@zslab.test");
                jdbc.update("DELETE FROM buyer_profile WHERE user_id IN (SELECT id FROM `user` WHERE email = ?)",
                        "cookie-signup@zslab.test");
                jdbc.update("DELETE FROM `user` WHERE id IN (?, ?, ?) OR email = ?",
                        BUYER_ID, SELLER_USER_ID, ADMIN_ID, "cookie-signup@zslab.test");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

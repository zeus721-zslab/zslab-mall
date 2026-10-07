package com.zslab.mall.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.zslab.mall.auth.exception.PasswordResetTokenInvalidException;
import com.zslab.mall.auth.service.PasswordResetService;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthCookies;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.order.event.OrderPlaced;
import com.zslab.mall.support.AbstractIntegrationTest;
import io.jsonwebtoken.Jwts;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 비밀번호 재설정 E2E(D-269·실 MariaDB·실 SMTP 수신). {@code EMAIL_SENDER=smtp} 컨텍스트에서 GreenMail(테스트 내 SMTP 서버·포트 3025)로
 * 메일을 실제로 받아 링크의 토큰으로 확정한다. 발급은 서비스 전용 단일 스레드 실행기에서 비동기로 일어나므로 메일 도착을 기다린다.
 *
 * <p><b>미발송 판정</b>: 실행기가 단일 스레드 FIFO라, 미발송 대상 요청 뒤에 구매자 요청을 하나 더 넣고 그 메일이 도착하면 앞 요청 처리가 끝난 것이다.
 * 그때 받은 메일이 구매자 1통뿐이면 앞 요청은 보내지 않은 것이다(고정 대기 없음).
 *
 * <p>가드(RED 선증명 대상): 만료 토큰 거부 · 재사용 거부 · 동시 확정 1회 · notification_log 마스킹 · 관리자 겸직·탈퇴 미발송 · 탈퇴 후 확정 거부.
 * 시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally)·모든 SQL은 ? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zslab.notification.email-sender=smtp",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.username=no-reply@zslab.test",
        "spring.mail.password=unused",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false"
})
class PasswordResetIntegrationTest extends AbstractIntegrationTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication())
            .withPerMethodLifecycle(false);

    private static final String REQUEST_URL = "/api/v1/auth/password-reset/request";
    private static final String CONFIRM_URL = "/api/v1/auth/password-reset/confirm";
    private static final String LOGIN_URL = "/api/v1/auth/buyer/login";
    private static final String LINK_PREFIX = "https://zslab-mall.duckdns.org/reset-password?token=";
    private static final Pattern LINK_PATTERN = Pattern.compile(Pattern.quote(LINK_PREFIX) + "([A-Za-z0-9_-]+)");
    private static final long MAIL_WAIT_MILLIS = 15_000L;
    private static final String RESET_SUBJECT = "[zslab-mall] 비밀번호 재설정 안내";
    private static final long BACKDATE_MILLIS = 5_000L;
    private static final long TOKEN_TTL_MILLIS = 3_600_000L;

    private static final long BUYER_ID = 9870L;
    private static final long SELLER_MEMBER_ID = 9871L;
    private static final long ADMIN_BUYER_ID = 9872L;
    private static final long WITHDRAWN_BUYER_ID = 9873L;
    private static final long SELLER_ID = 9870L;
    private static final long ORDER_ID = 9870L;
    private static final String BUYER_EMAIL = "reset-buyer@zslab.test";
    private static final String SELLER_MEMBER_EMAIL = "reset-seller@zslab.test";
    private static final String ADMIN_BUYER_EMAIL = "reset-admin@zslab.test";
    private static final String WITHDRAWN_BUYER_EMAIL = "reset-withdrawn@zslab.test";
    private static final String UNKNOWN_EMAIL = "reset-nobody@zslab.test";
    private static final String OLD_PASSWORD = "old-password-1";
    private static final String NEW_PASSWORD = "new-password-2";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private PasswordResetService passwordResetService;
    @Autowired
    private NotificationService notificationService;
    @Value("${jwt.secret}")
    private String jwtSecret;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        tx = new TransactionTemplate(txManager);
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        cleanup();
        seed();
    }

    @AfterEach
    void tearDown() throws Exception {
        awaitResetLogsCommitted();
        cleanup();
    }

    @Test
    @DisplayName("(1) EMAIL_SENDER=smtp → 사용 가능 여부 true")
    void availability_smtp_true() throws Exception {
        mockMvc.perform(get("/api/v1/auth/password-reset/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("(2) 성공 흐름: 요청 202 → 메일 수신 → 링크 토큰으로 확정 204 → 새 비밀번호 로그인 200 · 옛 비밀번호 401 · 옛 세션 401")
    void requestMailConfirm_thenNewPasswordLogin_andOldSessionRevoked() throws Exception {
        Cookie oldSession = new Cookie(AuthCookies.BUYER_COOKIE, backdatedToken(BUYER_ID));
        mockMvc.perform(get("/api/v1/users/me").cookie(oldSession)).andExpect(status().isOk());

        requestReset(BUYER_EMAIL);
        MimeMessage mail = awaitMails(1).get(0);
        assertThat(mail.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo(BUYER_EMAIL);
        assertThat(mail.getSubject()).isEqualTo(RESET_SUBJECT);
        String token = tokenFrom(mail);

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(post(LOGIN_URL).with(authHeaders.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(BUYER_EMAIL, NEW_PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(post(LOGIN_URL).with(authHeaders.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(BUYER_EMAIL, OLD_PASSWORD)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me").cookie(oldSession)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT used_at FROM password_reset_token WHERE token_hash = ?", LocalDateTime.class, sha256(token)))
                .isNotNull();
    }

    @Test
    @DisplayName("(3) 가드: 만료 토큰 → 400 PASSWORD_RESET_TOKEN_INVALID · 비밀번호 불변")
    void expiredToken_rejected() throws Exception {
        String rawToken = "expired-token-raw-value-for-it-0000000000";
        seedToken(BUYER_ID, rawToken, LocalDateTime.now().minusMinutes(1));
        String hashBefore = passwordHash(BUYER_ID);

        confirm(rawToken, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"));

        assertThat(passwordHash(BUYER_ID)).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("(4) 가드: 같은 토큰 재사용 → 첫 확정 204 · 두 번째 400 · 두 번째 비밀번호로 바뀌지 않음")
    void reusedToken_rejected() throws Exception {
        String rawToken = "reuse-token-raw-value-for-it-000000000000";
        seedToken(BUYER_ID, rawToken, LocalDateTime.now().plusMinutes(10));

        confirm(rawToken, NEW_PASSWORD).andExpect(status().isNoContent());
        confirm(rawToken, "another-password-3")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"));

        assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordHash(BUYER_ID))).isTrue();
    }

    @Test
    @DisplayName("(5) 가드: 같은 토큰 동시 확정 2건 → 정확히 1건만 성공")
    void concurrentConfirm_onlyOneSucceeds() throws Exception {
        String rawToken = "concurrent-token-raw-value-for-it-0000000";
        seedToken(BUYER_ID, rawToken, LocalDateTime.now().plusMinutes(10));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String password : List.of("concurrent-pass-a", "concurrent-pass-b")) {
                Callable<Boolean> attempt = () -> {
                    start.await();
                    try {
                        passwordResetService.confirm(rawToken, password);
                        return true;
                    } catch (PasswordResetTokenInvalidException rejected) {
                        return false;
                    }
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                successes += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("(6) 새 요청은 이전 미사용 토큰을 무효화한다 — 첫 메일 토큰 400 · 두 번째 메일 토큰 204")
    void newRequest_invalidatesPreviousToken() throws Exception {
        requestReset(BUYER_EMAIL);
        String firstToken = tokenFrom(awaitMails(1).get(0));
        requestReset(BUYER_EMAIL);
        String secondToken = tokenFrom(awaitMails(2).get(1));

        confirm(firstToken, NEW_PASSWORD).andExpect(status().isBadRequest());
        confirm(secondToken, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("(7) 열거 방지: 미가입·판매자·구매자 이메일 모두 같은 202·빈 본문 · 메일은 구매자에게만")
    void unknownAndSellerEmails_sameResponse_noMail() throws Exception {
        MvcResult unknown = requestReset(UNKNOWN_EMAIL);
        MvcResult seller = requestReset(SELLER_MEMBER_EMAIL);
        MvcResult buyer = requestReset(BUYER_EMAIL);

        for (MvcResult result : List.of(unknown, seller, buyer)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(202);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }
        // 단일 스레드 FIFO — 구매자 메일이 오면 앞의 두 요청 처리도 끝났다.
        List<MimeMessage> mails = awaitMails(1);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo(BUYER_EMAIL);
        assertThat(tokenCount(SELLER_MEMBER_ID)).isZero();
    }

    @Test
    @DisplayName("(10) 가드: 관리자 역할 겸직·탈퇴 구매자 이메일 → 같은 202 · 메일·토큰 없음")
    void adminAndWithdrawnBuyers_noMail() throws Exception {
        requestReset(ADMIN_BUYER_EMAIL);
        requestReset(WITHDRAWN_BUYER_EMAIL);
        requestReset(BUYER_EMAIL);

        List<MimeMessage> mails = awaitMails(1);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo(BUYER_EMAIL);
        assertThat(tokenCount(ADMIN_BUYER_ID)).isZero();
        assertThat(tokenCount(WITHDRAWN_BUYER_ID)).isZero();
    }

    @Test
    @DisplayName("(11) 가드: 발급 뒤 탈퇴한 회원의 유효 토큰 확정 → 400 · 비밀번호 불변 · 사용 처리도 롤백(used_at NULL)")
    void confirmAfterWithdrawal_rejectedAndRolledBack() throws Exception {
        String rawToken = "withdrawn-token-raw-value-for-it-00000000";
        seedToken(BUYER_ID, rawToken, LocalDateTime.now().plusMinutes(10));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE `user` SET withdrawn_at = NOW(6) WHERE id = ?", BUYER_ID));
        String hashBefore = passwordHash(BUYER_ID);

        confirm(rawToken, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"));

        assertThat(passwordHash(BUYER_ID)).isEqualTo(hashBefore);
        assertThat(jdbc.queryForObject("SELECT used_at FROM password_reset_token WHERE token_hash = ?", LocalDateTime.class, sha256(rawToken)))
                .isNull();
    }

    @Test
    @DisplayName("(8) 가드: notification_log 저장본은 마스킹 — 토큰·링크 원문 없음(발송본에는 있음)")
    void notificationLog_masksResetLink() throws Exception {
        requestReset(BUYER_EMAIL);
        String token = tokenFrom(awaitMails(1).get(0));
        awaitResetLogsCommitted();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT channel, status, content FROM notification_log WHERE recipient_user_id = ? AND template_code = 'TPL_PASSWORD_RESET'",
                BUYER_ID);
        assertThat(row.get("channel")).isEqualTo("EMAIL");
        assertThat(row.get("status")).isEqualTo("SENT");
        String content = (String) row.get("content");
        assertThat(content).contains("****").doesNotContain(token).doesNotContain("token=");
    }

    @Test
    @DisplayName("(9) 회귀: SMTP 활성 시 기존 이벤트 알림 메일도 실발송 1통 · 저장본은 원문 그대로(sensitive=false)")
    void existingOrderNotification_deliveredViaSmtp() throws Exception {
        String orderPublicId = pid("ord_", "T269ORD");
        seedOrder(orderPublicId);

        tx.executeWithoutResult(s -> notificationService.recordOrderPlaced(new OrderPlaced(orderPublicId, ORDER_ID, LocalDateTime.now())));

        MimeMessage mail = awaitMails(1).get(0);
        assertThat(mail.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo(BUYER_EMAIL);
        assertThat(mail.getSubject()).isEqualTo("주문 접수");
        String expectedContent = "주문 " + orderPublicId + "이(가) 접수되었습니다.";
        assertThat(((String) mail.getContent()).trim()).isEqualTo(expectedContent);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, content FROM notification_log WHERE target_type = 'ORDER' AND target_id = ?", ORDER_ID);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("content")).isEqualTo(expectedContent);
    }

    // ---------- helpers ----------

    private MvcResult requestReset(String email) throws Exception {
        return mockMvc.perform(post(REQUEST_URL).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String newPassword) throws Exception {
        return mockMvc.perform(post(CONFIRM_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"));
    }

    private List<MimeMessage> awaitMails(int count) {
        assertThat(GREEN_MAIL.waitForIncomingEmail(MAIL_WAIT_MILLIS, count)).as("메일 %d통 수신 대기", count).isTrue();
        return List.of(GREEN_MAIL.getReceivedMessages());
    }

    /**
     * 재설정 메일의 발송 기록은 SMTP 전송 뒤 발송 트랜잭션이 커밋할 때 보인다. 받은 재설정 메일 수만큼 확정(SENT·FAILED) 행이 보일 때까지 기다린다 —
     * 단언 전과 정리 전에 호출해 실행기의 늦은 커밋이 다음 테스트로 새지 않게 한다.
     */
    private void awaitResetLogsCommitted() throws Exception {
        long resetMails = 0;
        for (MimeMessage mail : GREEN_MAIL.getReceivedMessages()) {
            resetMails += RESET_SUBJECT.equals(mail.getSubject()) ? 1 : 0;
        }
        long expected = resetMails;
        await().atMost(Duration.ofMillis(MAIL_WAIT_MILLIS)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE template_code = 'TPL_PASSWORD_RESET' AND status <> 'PENDING' "
                        + "AND recipient_user_id IN (?, ?, ?, ?)", Long.class, BUYER_ID, SELLER_MEMBER_ID, ADMIN_BUYER_ID, WITHDRAWN_BUYER_ID)
                == expected);
    }

    private static String tokenFrom(MimeMessage mail) throws Exception {
        Matcher matcher = LINK_PATTERN.matcher((String) mail.getContent());
        assertThat(matcher.find()).as("메일 본문에 재설정 링크").isTrue();
        return matcher.group(1);
    }

    private String backdatedToken(long userId) {
        long issued = System.currentTimeMillis() - BACKDATE_MILLIS;
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", ActorRole.BUYER.name())
                .issuedAt(new Date(issued))
                .expiration(new Date(issued + TOKEN_TTL_MILLIS))
                .signWith(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256)
                .compact();
    }

    private static String sha256(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private String passwordHash(long userId) {
        return jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = ?", String.class, userId);
    }

    private int tokenCount(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", Integer.class, userId);
    }

    private void seedToken(long userId, String rawToken, LocalDateTime expiresAt) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.update("INSERT INTO password_reset_token (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, NOW(6))",
                        userId, sha256(rawToken), expiresAt);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                seedUser(BUYER_ID, BUYER_EMAIL, "T269B1");
                seedUser(SELLER_MEMBER_ID, SELLER_MEMBER_EMAIL, "T269S1");
                seedUser(ADMIN_BUYER_ID, ADMIN_BUYER_EMAIL, "T269A1");
                // 관리자 역할 겸직(D-204 X2 — ADMIN_OPERATOR는 BUYER 겸직)도 재설정 대상이 아니다.
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'ADMIN_OPERATOR'",
                        ADMIN_BUYER_ID);
                seedUser(WITHDRAWN_BUYER_ID, WITHDRAWN_BUYER_EMAIL, "T269W1");
                jdbc.update("UPDATE `user` SET withdrawn_at = NOW(6) WHERE id = ?", WITHDRAWN_BUYER_ID);
                // 판매자 구성원은 BUYER를 겸해도(D-189 α 겸직) 재설정 대상이 아니다.
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '재설정IT셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", SELLER_ID, pid("slr_", "T269SLR"));
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                        + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", SELLER_MEMBER_ID, SELLER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedUser(long id, String email, String tag) {
        jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                id, pid("usr_", tag), email, passwordEncoder.encode(OLD_PASSWORD));
        jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = 'BUYER'", id);
    }

    private void seedOrder(String orderPublicId) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                                + "discount_amount, shipping_fee, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, 'PAID', 10000, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, orderPublicId, BUYER_ID, "ORDT269" + ORDER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (long userId : List.of(BUYER_ID, SELLER_MEMBER_ID, ADMIN_BUYER_ID, WITHDRAWN_BUYER_ID)) {
                    jdbc.update("DELETE FROM password_reset_token WHERE user_id = ?", userId);
                    jdbc.update("DELETE FROM notification_log WHERE recipient_user_id = ?", userId);
                    jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
                    jdbc.update("DELETE FROM seller_user WHERE user_id = ?", userId);
                    jdbc.update("DELETE FROM `user` WHERE id = ?", userId);
                }
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

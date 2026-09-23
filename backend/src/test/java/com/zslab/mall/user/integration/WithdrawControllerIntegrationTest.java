package com.zslab.mall.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import java.sql.Timestamp;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 본인 회원 탈퇴 endpoint E2E 통합 테스트(Track 60 BL-5·실 MariaDB). HTTP → UserController → UserService → DB 흐름을
 * 실 커밋·HTTP 경유로 검증한다. 커버: 성공 204·재탈퇴 멱등(최초 시각 유지)·탈퇴 후 재로그인 차단 실효(AuthService 가드 자동 발동)·
 * 미인증 401.
 *
 * <p>재로그인 차단 케이스는 우연일치를 배제하기 위해 탈퇴 <b>전</b> 로그인 200(계정이 완전히 로그인 가능함)을 먼저 실증한 뒤
 * 탈퇴 <b>후</b> 동일 credential 로그인 401을 확인한다. 그래서 seed는 실 {@link PasswordEncoder}(BCrypt) password_hash +
 * BUYER user_role(V11 seed를 code로 조회) 매핑을 갖춘 로그인 가능 유저 1행이다. DB 커밋을 JdbcTemplate 직접 조회로
 * 검증하므로 클래스에 @Transactional을 두지 않는다.
 */
@AutoConfigureMockMvc
class WithdrawControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/users/me/withdraw";
    private static final String LOGIN_URL = "/api/v1/auth/login";

    private static final long WITHDRAW_USER_ID = 9680L;
    private static final long ORDER_ACTIVE_ID = 96801L;
    private static final long ORDER_TERMINAL_ID = 96810L;
    private static final long ORDER_ITEM_ID = 96801L;
    private static final long CLAIM_ID = 96801L;
    private static final long DUMMY_FK_ID = 1L; // FK_CHECKS=0 시드·상품/셀러 실존 불요
    private static final String EMAIL = "withdraw-it@zslab.test";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String FAILURE_MESSAGE = "Invalid email or password.";

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
    @DisplayName("(1) 인증 후 탈퇴 → 204 + DB withdrawn_at 마킹")
    void withdraw_returns204_marksWithdrawnAt() throws Exception {
        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isNoContent());

        Timestamp withdrawnAt = jdbc.queryForObject(
                "SELECT withdrawn_at FROM `user` WHERE id=?", Timestamp.class, WITHDRAW_USER_ID);
        assertThat(withdrawnAt).isNotNull();
    }

    @Test
    @DisplayName("(2) 탈퇴 후 재요청 → 탈퇴 회원 토큰 401(Track 84) + withdrawn_at 최초 시각 유지(덮어쓰기 없음)")
    void withdraw_thenRetry_returns401_keepsFirstTimestamp() throws Exception {
        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isNoContent());
        Timestamp first = jdbc.queryForObject(
                "SELECT withdrawn_at FROM `user` WHERE id=?", Timestamp.class, WITHDRAW_USER_ID);

        // 탈퇴 회원의 토큰은 발급 시점과 무관하게 인증 필터가 거부한다(withdrawn_at != null → 401 UNAUTHENTICATED).
        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        Timestamp second = jdbc.queryForObject(
                "SELECT withdrawn_at FROM `user` WHERE id=?", Timestamp.class, WITHDRAW_USER_ID);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("(5) 진행 중 주문(PAID) 보유 → 409 MEMBER_ACTIVITY_IN_PROGRESS·withdrawn_at NULL 유지")
    void withdraw_withActiveOrder_returns409() throws Exception {
        seedOrder(ORDER_ACTIVE_ID, "PAID", "PAID");

        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ACTIVITY_IN_PROGRESS"));
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM `user` WHERE id=?", Timestamp.class, WITHDRAW_USER_ID))
                .isNull();
    }

    @Test
    @DisplayName("(5-2) 결제 전 주문(PENDING_PAYMENT·품목 ORDERED) 보유 → 409 MEMBER_ACTIVITY_IN_PROGRESS(결제 전 단계는 주문 상태로 판정·Track 104-4)")
    void withdraw_withPendingPaymentOrder_returns409() throws Exception {
        seedOrder(ORDER_ACTIVE_ID, "PENDING_PAYMENT", "ORDERED");

        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ACTIVITY_IN_PROGRESS"));
    }

    @Test
    @DisplayName("(6) 종결 주문만 보유(CONFIRMED·CANCELLED·PARTIAL_CANCEL·PAYMENT_EXPIRED — 품목도 각 요약에 맞는 상태) → 204")
    void withdraw_withTerminalOrdersOnly_returns204() throws Exception {
        seedOrder(ORDER_TERMINAL_ID, "CONFIRMED", "CONFIRMED", "RETURNED");
        seedOrder(ORDER_TERMINAL_ID + 1, "CANCELLED", "CANCELLED");
        seedOrder(ORDER_TERMINAL_ID + 2, "PARTIAL_CANCEL", "CANCELLED", "CONFIRMED");
        seedOrder(ORDER_TERMINAL_ID + 3, "PAYMENT_EXPIRED", "ORDERED");

        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("(6-2) 판별(Track 104-4 P4): 주문 요약 CONFIRMED(옛 종결 집합) + 품목 하나 SHIPPING → 409(품목 사실 판정) — 옛 주문 상태 판정이면 204")
    void withdraw_orderStatusTerminalButItemInProgress_returns409() throws Exception {
        seedOrder(ORDER_TERMINAL_ID, "CONFIRMED", "CONFIRMED", "SHIPPING");

        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ACTIVITY_IN_PROGRESS"));
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM `user` WHERE id=?", Timestamp.class, WITHDRAW_USER_ID))
                .isNull();
    }

    @Test
    @DisplayName("(7) 종결 주문의 활성 클레임(REQUESTED) 보유 → 409 MEMBER_ACTIVITY_IN_PROGRESS")
    void withdraw_withActiveClaim_returns409() throws Exception {
        seedOrder(ORDER_TERMINAL_ID, "CONFIRMED");
        seedOrderItem(ORDER_ITEM_ID, ORDER_TERMINAL_ID);
        seedClaim(CLAIM_ID, ORDER_ITEM_ID, "REQUESTED");

        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ACTIVITY_IN_PROGRESS"));
    }

    @Test
    @DisplayName("(8) 종결 클레임(COMPLETED)만 보유 → 204·탈퇴 후 프로필 조회는 401(기존 토큰 무효)")
    void withdraw_withCompletedClaimOnly_returns204_thenTokenRejected() throws Exception {
        seedOrder(ORDER_TERMINAL_ID, "CONFIRMED");
        seedOrderItem(ORDER_ITEM_ID, ORDER_TERMINAL_ID);
        seedClaim(CLAIM_ID, ORDER_ITEM_ID, "COMPLETED");
        HttpHeaders tokenBeforeWithdraw = authHeaders.buyer(WITHDRAW_USER_ID);

        mockMvc.perform(post(URL).headers(tokenBeforeWithdraw))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/me").headers(tokenBeforeWithdraw))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("(3) 탈퇴 후 재로그인 차단 실효 → 탈퇴 전 200·탈퇴 후 401(ACCOUNT_DISABLED 통합 401)")
    void withdraw_thenLogin_returns401() throws Exception {
        // 탈퇴 전: 동일 credential 로그인 성공(계정이 완전히 로그인 가능함을 실증·우연일치 배제).
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());

        mockMvc.perform(post(URL).headers(authHeaders.buyer(WITHDRAW_USER_ID)))
                .andExpect(status().isNoContent());

        // 탈퇴 후: withdrawn_at != null 가드 발동 → 사유 무관 401·동일 메시지(계정 열거 방지).
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD, "BUYER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(FAILURE_MESSAGE));
    }

    @Test
    @DisplayName("(4) 미인증(토큰 없음) → 401 UNAUTHENTICATED")
    void withdraw_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(URL).headers(new HttpHeaders()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- seed·helpers (? positional 바인딩·정적 SQL·SQL injection 없음) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        WITHDRAW_USER_ID, pid("usr_", "WDRUSR"), EMAIL, passwordEncoder.encode(PASSWORD));
                // 로그인 fail-closed RBAC(BUYER) 통과에 user_role 매핑 실존 필요. role_id는 V11 seed를 code로 조회(하드코딩 금지).
                jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) "
                                + "SELECT ?, id, NOW(6) FROM role WHERE code = ?",
                        WITHDRAW_USER_ID, "BUYER");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /**
     * 탈퇴 가드 픽스처(Track 84): 주문·품목·클레임은 FK_CHECKS=0으로 상품·셀러 없이 심는다. 가드는 결제 전 주문 상태·결제 후 품목 상태를
     * 보므로(Track 104-4) 주문 요약값과 맞는 품목을 itemStatuses로 함께 심는다(품목 id = 주문 id × 10 + 순번).
     */
    private void seedOrder(long orderId, String status, String... itemStatuses) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                                + "shipping_fee, ordered_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 10000, 0, 0, NOW(6), NOW(6), NOW(6))",
                        orderId, pid("ord_", "WDR" + orderId), WITHDRAW_USER_ID, "ORDWDR" + orderId, status);
                for (int index = 0; index < itemStatuses.length; index++) {
                    long orderItemId = orderId * 10 + index;
                    jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                                    + "total_price, item_status, product_name, created_at, updated_at, commission_rate) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, ?, '탈퇴가드상품', NOW(6), NOW(6), 1000)",
                            orderItemId, pid("oit_", "WDRI" + orderItemId), orderId, DUMMY_FK_ID, DUMMY_FK_ID, DUMMY_FK_ID,
                            itemStatuses[index]);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedOrderItem(long orderItemId, long orderId) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                                + "total_price, item_status, product_name, created_at, updated_at, commission_rate) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, 'CONFIRMED', '탈퇴가드상품', NOW(6), NOW(6), 1000)",
                        orderItemId, pid("oit_", "WDR" + orderItemId), orderId, DUMMY_FK_ID, DUMMY_FK_ID, DUMMY_FK_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedClaim(long claimId, long orderItemId, String status) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, previous_order_item_status, "
                                + "requested_by, requested_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'RETURN', 'BUYER_CHANGED_MIND', ?, 'DELIVERED', ?, NOW(6), NOW(6), NOW(6))",
                        claimId, pid("clm_", "WDR" + claimId), orderItemId, status, WITHDRAW_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE id = ?", CLAIM_ID);
                jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE buyer_id = ?)", WITHDRAW_USER_ID);
                jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", WITHDRAW_USER_ID);
                // FK RESTRICT 회귀 방지: 자식(user_role)을 user보다 먼저 삭제.
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", WITHDRAW_USER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", WITHDRAW_USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private String loginBody(String email, String password, String role) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

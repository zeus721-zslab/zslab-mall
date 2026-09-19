package com.zslab.mall.seller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 상태 가드 매트릭스 통합 테스트(Track 90-A·D-187 §8 이월·실 MariaDB). 상태 4종 × {로그인 / 조회 API / 쓰기 API}.
 *
 * <ul>
 *   <li>로그인 {@code POST /api/v1/auth/login role=SELLER}: ACTIVE·SUSPENDED 200 / PENDING·TERMINATED 401(다른 사유와 같은 문구).</li>
 *   <li>조회 {@code GET /api/v1/seller/settlements}(resolver 경유): ACTIVE·SUSPENDED 200 / PENDING·TERMINATED 401 UNAUTHENTICATED.</li>
 *   <li>쓰기 {@code POST /api/v1/seller/inventories/{var}/mark-inbound}: ACTIVE 200(실 재고 반영) / SUSPENDED 403 SELLER_SUSPENDED·재고 무변경 /
 *       PENDING·TERMINATED 401·재고 무변경.</li>
 * </ul>
 * API 요청은 {@link AuthHeaders}로 직접 발급한 토큰을 쓰므로 로그인 가드와 독립적으로 resolver 가드를 검증한다(발급 후 상태 변경 시나리오와 동형).
 * 다중 소속(허용+차단 혼재)은 uk_seller_user_user_id(V12)로 행 자체를 만들 수 없어 케이스가 성립하지 않는다.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩(SQL injection 없음).
 */
@AutoConfigureMockMvc
class SellerStatusGuardIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 9560L;
    private static final long SELLER_ID = 9560L;
    private static final long PRODUCT_ID = 9560L;
    private static final long VARIANT_ID = 9560L;
    private static final long INVENTORY_ID = 9560L;
    private static final long DUMMY_FK_ID = 9560L;
    private static final String EMAIL = "status-guard@zslab.test";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String FAILURE_MESSAGE = "Invalid email or password.";
    /** 403 detail 고정 문구(SellerSuspendedException 메시지·상수 리터럴). 식별자(sellerId·userId·status)가 섞이면 이 단언이 깨진다(외부 검토 R2). */
    private static final String SUSPENDED_DETAIL = "정지된 판매자는 조회만 가능합니다";
    private static final String VARIANT_PID = pid("var_", "SSGVAR");
    private static final int INITIAL_ON_HAND = 10;
    private static final int INBOUND_QTY = 5;
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String READ_URL = "/api/v1/seller/settlements";
    private static final String WRITE_URL = "/api/v1/seller/inventories/" + VARIANT_PID + "/mark-inbound";

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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    // ---------- 로그인 ----------

    @ParameterizedTest(name = "{0} 셀러 로그인 → 200")
    @EnumSource(value = SellerStatus.class, names = {"ACTIVE", "SUSPENDED"})
    @DisplayName("로그인: 세션 허용 상태 → 200")
    void login_sessionAllowed_returns200(SellerStatus status) throws Exception {
        seed(status);

        mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(loginBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString());
    }

    @ParameterizedTest(name = "{0} 셀러 로그인 → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("로그인: 세션 불가 상태 → 401·다른 실패 사유와 같은 문구(은닉)")
    void login_sessionDenied_returns401(SellerStatus status) throws Exception {
        seed(status);

        mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(loginBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(FAILURE_MESSAGE));
    }

    // ---------- 조회 API(resolver) ----------

    @ParameterizedTest(name = "{0} 셀러 GET settlements → 200")
    @EnumSource(value = SellerStatus.class, names = {"ACTIVE", "SUSPENDED"})
    @DisplayName("조회: 세션 허용 상태 → 200")
    void read_sessionAllowed_returns200(SellerStatus status) throws Exception {
        seed(status);

        mockMvc.perform(get(READ_URL).headers(authHeaders.seller(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @ParameterizedTest(name = "{0} 셀러 GET settlements → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("조회: 세션 불가 상태 → 401 UNAUTHENTICATED(유효 토큰이어도 즉시 차단)")
    void read_sessionDenied_returns401(SellerStatus status) throws Exception {
        seed(status);

        mockMvc.perform(get(READ_URL).headers(authHeaders.seller(USER_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- 쓰기 API(resolver) ----------

    @ParameterizedTest(name = "{0} 셀러 POST mark-inbound → 200")
    @EnumSource(value = SellerStatus.class, names = {"ACTIVE"})
    @DisplayName("쓰기: ACTIVE → 200·재고 반영·history INBOUND 1행")
    void write_active_returns200(SellerStatus status) throws Exception {
        seed(status);

        mockMvc.perform(post(WRITE_URL).headers(authHeaders.seller(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(inboundBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(INITIAL_ON_HAND + INBOUND_QTY));

        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND + INBOUND_QTY);
        // SUSPENDED 케이스(history 0행)와 대조: 쓰기 경로 진입 시 INBOUND 이력 1행이 남는다.
        assertThat(historyCount()).isEqualTo(1);
        assertThat(historyChangeType()).isEqualTo("INBOUND");
    }

    @ParameterizedTest(name = "{0} 셀러 POST mark-inbound → 403 SELLER_SUSPENDED")
    @EnumSource(value = SellerStatus.class, names = {"SUSPENDED"})
    @DisplayName("쓰기: SUSPENDED → 403 SELLER_SUSPENDED·detail 고정 문구(내부 식별자 미노출)·재고 무변경·history 0행(조회는 같은 토큰으로 200)")
    void write_suspended_returns403(SellerStatus status) throws Exception {
        seed(status);

        mockMvc.perform(post(WRITE_URL).headers(authHeaders.seller(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(inboundBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_SUSPENDED"))
                // handler가 exception.getMessage()를 detail로 그대로 내보내므로 고정 문구 일치로 식별자 유입을 막는다.
                .andExpect(jsonPath("$.detail").value(SUSPENDED_DETAIL));

        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
        // on_hand 무변경은 최종 상태를, history 0행은 쓰기 경로 미진입을 증명한다. 차단이 트랜잭션 진입 전임을 고정.
        assertThat(historyCount()).isZero();
        mockMvc.perform(get(READ_URL).headers(authHeaders.seller(USER_ID))).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} 셀러 POST mark-inbound → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("쓰기: 세션 불가 상태 → 401 UNAUTHENTICATED·재고 무변경")
    void write_sessionDenied_returns401(SellerStatus status) throws Exception {
        seed(status);

        mockMvc.perform(post(WRITE_URL).headers(authHeaders.seller(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(inboundBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
    }

    // ---------- seed·helpers ----------

    /** user(비밀번호·BUYER role 없음 → SELLER 로그인은 seller_user만으로 판정)·seller(status)·SELLER_OWNER 매핑·상품·변형·재고. */
    private void seed(SellerStatus status) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, email, password_hash, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "SSGUSR"), EMAIL, passwordEncoder.encode(PASSWORD));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '상태가드셀러', '대표', ?, NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "SSGSLR"), status.name());
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        USER_ID, SELLER_ID);
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, '상태가드상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "SSGPRD"), SELLER_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                                + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCSSG', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, VARIANT_PID, PRODUCT_ID, DUMMY_FK_ID);
                jdbc.update("INSERT INTO inventory (id, variant_id, quantity_on_hand, quantity_reserved, quantity_available, "
                                + "created_at, updated_at) VALUES (?, ?, ?, 0, ?, NOW(6), NOW(6))",
                        INVENTORY_ID, VARIANT_ID, INITIAL_ON_HAND, INITIAL_ON_HAND);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM inventory_history WHERE inventory_id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM inventory WHERE id = ?", INVENTORY_ID);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", USER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private Integer onHand() {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE id = ?", Integer.class, INVENTORY_ID);
    }

    private Integer historyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_history WHERE inventory_id = ?", Integer.class, INVENTORY_ID);
    }

    private String historyChangeType() {
        return jdbc.queryForObject("SELECT change_type FROM inventory_history WHERE inventory_id = ?", String.class, INVENTORY_ID);
    }

    private static String loginBody() {
        return "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"SELLER\"}";
    }

    private static String inboundBody() {
        return "{\"quantity\":" + INBOUND_QTY + ",\"reason\":\"상태 가드 입고\"}";
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

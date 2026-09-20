package com.zslab.mall.seller.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 셀러 본인 조회 통합 테스트(Track 90-B-1·실 MariaDB·HTTP 경유). 상태 4종 × GET /api/v1/seller/me(D-190 세션 허용 = ACTIVE·SUSPENDED 200 /
 * PENDING·TERMINATED 401) · 응답 키 화이트리스트(예상 밖 필드 추가 시 실패) · PENDING 정산 건수(CONFIRMED·PAID 미포함) · 주 계좌 여부 · BUYER 403.
 *
 * <p>시드는 {@link TransactionTemplate} + {@code FOREIGN_KEY_CHECKS=0}·? 바인딩. 계좌 시드는 id projection만 읽히므로(AES 복호 없음) 평문 계좌번호로 둔다.
 */
@AutoConfigureMockMvc
class SellerMeControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String ME_URL = "/api/v1/seller/me";
    private static final long USER_ID = 9610L;
    private static final long BUYER_ID = 9611L;
    private static final long SELLER_ID = 9610L;
    private static final long BANK_ACCOUNT_ID = 9610L;
    private static final long SETTLEMENT_PENDING_1 = 9610L;
    private static final long SETTLEMENT_PENDING_2 = 9611L;
    private static final long SETTLEMENT_CONFIRMED = 9612L;
    private static final String SELLER_PID = pid("slr_", "MESLR");
    /** 응답 키 화이트리스트 — 필드가 늘면 여기와 SellerMeResponse를 함께 바꿔야 한다(조용한 노출 확장 방지). */
    private static final Set<String> RESPONSE_KEYS = Set.of(
            "sellerPublicId", "companyName", "status", "roleCode", "pendingSettlementCount", "bankAccountRegistered");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("T1 ACTIVE 셀러 → 200·필드 화이트리스트·상호·역할 OWNER·PENDING 정산 2건(CONFIRMED 제외)·주 계좌 등록")
    void me_active_returnsWhitelistedFields() throws Exception {
        seed(SellerStatus.ACTIVE, true);

        String body = mockMvc.perform(get(ME_URL).headers(authHeaders.seller(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerPublicId").value(SELLER_PID))
                .andExpect(jsonPath("$.companyName").value("본인조회셀러"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roleCode").value("SELLER_OWNER"))
                .andExpect(jsonPath("$.pendingSettlementCount").value(2))
                .andExpect(jsonPath("$.bankAccountRegistered").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(topLevelKeys(body)).containsExactlyInAnyOrderElementsOf(RESPONSE_KEYS);
    }

    @Test
    @DisplayName("T2 주 계좌 없음(비주계좌만) → bankAccountRegistered false · PENDING 정산 0건 → 0")
    void me_withoutPrimaryAccount_returnsFalse() throws Exception {
        seed(SellerStatus.ACTIVE, false);
        jdbc.update("DELETE FROM settlement WHERE id IN (?, ?)", SETTLEMENT_PENDING_1, SETTLEMENT_PENDING_2);

        mockMvc.perform(get(ME_URL).headers(authHeaders.seller(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingSettlementCount").value(0))
                .andExpect(jsonPath("$.bankAccountRegistered").value(false));
    }

    @Test
    @DisplayName("T3 SUSPENDED 셀러 → 200·status SUSPENDED(정지 안내 배너의 근거·GET이라 D-190 쓰기 가드 미적용)")
    void me_suspended_returns200() throws Exception {
        seed(SellerStatus.SUSPENDED, true);

        mockMvc.perform(get(ME_URL).headers(authHeaders.seller(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @ParameterizedTest(name = "{0} 셀러 GET me → 401")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("T4 세션 불가 상태 → 401 UNAUTHENTICATED")
    void me_sessionDenied_returns401(SellerStatus status) throws Exception {
        seed(status, true);

        mockMvc.perform(get(ME_URL).headers(authHeaders.seller(USER_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("T5 BUYER 토큰 → 403 · 무인증 → 401")
    void me_wrongRole_rejected() throws Exception {
        seed(SellerStatus.ACTIVE, true);

        mockMvc.perform(get(ME_URL).headers(authHeaders.buyer(BUYER_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(ME_URL)).andExpect(status().isUnauthorized());
    }

    // ---------- seed·helpers ----------

    private void seed(SellerStatus status, boolean primaryAccount) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                        USER_ID, pid("usr_", "MEUSR"));
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                                + "VALUES (?, ?, '본인조회셀러', '대표', ?, NOW(6), NOW(6))",
                        SELLER_ID, SELLER_PID, status.name());
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'",
                        USER_ID, SELLER_ID);
                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, "
                                + "is_primary, status, created_at, updated_at) VALUES (?, ?, '004', '123', '대표', ?, 'VERIFIED', NOW(6), NOW(6))",
                        BANK_ACCOUNT_ID, SELLER_ID, primaryAccount ? 1 : 0);
                seedSettlement(SETTLEMENT_PENDING_1, "2026-06-01 00:00:00", "2026-06-30 23:59:59", "PENDING");
                seedSettlement(SETTLEMENT_PENDING_2, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "PENDING");
                seedSettlement(SETTLEMENT_CONFIRMED, "2026-05-01 00:00:00", "2026-05-31 23:59:59", "CONFIRMED");
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedSettlement(long id, String periodStart, String periodEnd, String status) {
        jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, "
                        + "fee_amount, refund_amount, net_amount, commission_rate, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 20000, 2000, 0, 18000, 1000, ?, NOW(6), NOW(6))",
                id, SELLER_ID, BANK_ACCOUNT_ID, periodStart, periodEnd, status);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM settlement WHERE seller_id = ?", SELLER_ID);
                jdbc.update("DELETE FROM seller_bank_account WHERE seller_id = ?", SELLER_ID);
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", USER_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
                jdbc.update("DELETE FROM `user` WHERE id = ?", USER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private Set<String> topLevelKeys(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

package com.zslab.mall.settlement.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
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
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * 정산 전이(confirm·pay) endpoint E2E 통합 테스트(Track 49·실 MariaDB). HTTP → {@code AdminSettlementController} →
 * {@code SettlementTransitionService} → 비관적 락 조회·mutator → DB 실 커밋을 검증한다
 * ({@code AdminSettlementControllerIntegrationTest} 패턴 1:1).
 *
 * <p><b>커버</b>: T1 confirm 200(PENDING→CONFIRMED)·T2 pay 200(CONFIRMED→PAID·paid_at)·T3 confirm 422(PAID 불가역)·
 * T4 pay 422(PENDING 직접 지급)·T5 404(미존재)·T6 403(비ADMIN).
 *
 * <p><b>트랜잭션</b>: 실 커밋으로 전이를 구동하므로 클래스 {@code @Transactional} 없음. 시드/정리는 {@link TransactionTemplate}
 * + {@code FOREIGN_KEY_CHECKS=0}(try-finally). 전이 대상은 id 조회이므로 시각 세션TZ 트랩과 무관하다.
 */
@AutoConfigureMockMvc
class SettlementTransitionControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9680L;
    private static final long BUYER_ID = 9681L;
    private static final long SELLER_PENDING = 9601L;
    private static final long SELLER_CONFIRMED = 9602L;
    private static final long SELLER_PAID = 9603L;
    private static final long SELLER_PENDING2 = 9604L;
    private static final long SELLER_PAID2 = 9605L;
    private static final long SELLER_MIN = 9600L;
    private static final long SELLER_MAX = 9610L;
    private static final String BASE = "/api/v1/admin/settlements/";

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
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 confirm: PENDING → 200·CONFIRMED")
    void confirm_returns200() throws Exception {
        long id = seedSettlement(SELLER_PENDING, "PENDING");

        mockMvc.perform(post(BASE + id + "/confirm").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementId").value(id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paidAt").doesNotExist());
    }

    @Test
    @DisplayName("T2 pay: CONFIRMED → 200·PAID·paid_at 채움")
    void pay_returns200() throws Exception {
        long id = seedSettlement(SELLER_CONFIRMED, "CONFIRMED");

        mockMvc.perform(post(BASE + id + "/pay").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementId").value(id))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").exists());
    }

    @Test
    @DisplayName("T3 confirm: PAID → 422 SETTLEMENT_INVALID_STATE(불가역)")
    void confirm_onPaid_returns422() throws Exception {
        long id = seedSettlement(SELLER_PAID, "PAID");

        mockMvc.perform(post(BASE + id + "/confirm").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_INVALID_STATE"));
    }

    @Test
    @DisplayName("T4 pay: PENDING → 422 SETTLEMENT_INVALID_STATE(CONFIRMED 미경유)")
    void pay_onPending_returns422() throws Exception {
        long id = seedSettlement(SELLER_PENDING2, "PENDING");

        mockMvc.perform(post(BASE + id + "/pay").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_INVALID_STATE"));
    }

    @Test
    @DisplayName("T5 미존재 settlementId → 404 SETTLEMENT_NOT_FOUND")
    void transition_notFound_returns404() throws Exception {
        mockMvc.perform(post(BASE + "9999999/confirm").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("T6 비ADMIN: BUYER 토큰 → 403")
    void transition_nonAdmin_returns403() throws Exception {
        long id = seedSettlement(SELLER_PAID2, "PAID");

        mockMvc.perform(post(BASE + id + "/pay").headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isForbidden());
    }

    // ---------- seed·helpers ----------
    // 모든 시드 INSERT는 바인딩 파라미터 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    // ⚠️settlement는 fk_settlement_seller·fk_settlement_bank_account를 가지며, 전이 UPDATE는 앱 커넥션(FK_CHECKS=1)에서
    //   실행되어 부모 행을 요구한다. 따라서 seller·seller_bank_account 부모를 실제 시드한다(INSERT만 FK 우회하면 UPDATE서 터짐).
    //   계좌 id·bank_account_id는 seller_id와 동일 값으로 두어 범위 정리(BETWEEN)를 단순화한다.
    private long seedSettlement(long sellerId, String settlementStatus) {
        return tx.execute(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, "
                                + "created_at, updated_at) VALUES (?, ?, '정산셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))",
                        sellerId, pid("slr_", sellerId));
                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, "
                                + "is_primary, status, created_at, updated_at) "
                                + "VALUES (?, ?, '004', '123', '대표', 1, 'VERIFIED', NOW(6), NOW(6))",
                        sellerId, sellerId);
                jdbc.update("INSERT INTO settlement (seller_id, bank_account_id, period_start, period_end, "
                                + "gross_amount, fee_amount, commission_rate, refund_amount, net_amount, status, "
                                + "created_at, updated_at) "
                                + "VALUES (?, ?, NOW(6), NOW(6), 10000, 1000, 1000, 0, 9000, ?, NOW(6), NOW(6))",
                        sellerId, sellerId, settlementStatus);
                return jdbc.queryForObject(
                        "SELECT id FROM settlement WHERE seller_id = ?", Long.class, sellerId);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM settlement WHERE seller_id BETWEEN ? AND ?", SELLER_MIN, SELLER_MAX);
                jdbc.update("DELETE FROM seller_bank_account WHERE seller_id BETWEEN ? AND ?", SELLER_MIN, SELLER_MAX);
                jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", SELLER_MIN, SELLER_MAX);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /** 26자 public_id 접미를 seller_id 기반으로 생성한다(유일성 확보·범위 밖 접미 문자 회피). */
    private static String pid(String prefix, long sellerId) {
        return prefix + String.format("%026d", sellerId);
    }
}

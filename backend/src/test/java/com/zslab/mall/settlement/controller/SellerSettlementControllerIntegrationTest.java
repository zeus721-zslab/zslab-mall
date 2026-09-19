package com.zslab.mall.settlement.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
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

/**
 * 셀러 정산 조회 API E2E 통합 테스트(Track 85). SELLER 토큰(user.id → seller_user → seller.id)으로 본인 CONFIRMED·PAID만 보이고
 * PENDING·타 셀러·미존재는 404로 통일되는지, BUYER 403·미매핑 셀러 401을 검증한다.
 *
 * <p><b>시드</b>: 셀러 A(구성원 user 9495) 6월 CONFIRMED·5월 PENDING·4월 PAID(계좌 스냅샷) / 셀러 B 6월 CONFIRMED.
 */
@AutoConfigureMockMvc
class SellerSettlementControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long SELLER_A_USER = 9495L;
    private static final long UNMAPPED_USER = 9496L;
    private static final long BUYER_ID = 9497L;
    private static final long SELLER_A = 9495L;
    private static final long SELLER_B = 9496L;
    private static final long SELLER_A_ACCOUNT = 9495L;
    private static final long STL_A_JUNE_CONFIRMED = 9495L;
    private static final long STL_A_MAY_PENDING = 9496L;
    private static final long STL_A_APR_PAID = 9497L;
    private static final long STL_B_JUNE_CONFIRMED = 9498L;
    private static final String URL = "/api/v1/seller/settlements";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    // Track 89-F: 계좌번호 컬럼은 v1: 암호문(Converter strict) → 시드도 암호화해 INSERT한다
    @Autowired
    private AesGcmTextEncryptor bankAccountEncryptor;

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
    @DisplayName("S1 목록: 본인 CONFIRMED·PAID만 최신 기간순(6월·4월)·PENDING(5월)·타 셀러 제외")
    void list_visibleOnly() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].id").value(STL_A_JUNE_CONFIRMED))
                .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].netAmount").value(13500))
                .andExpect(jsonPath("$.items[0].scheduledPayDate").value("2026-07-20"))
                .andExpect(jsonPath("$.items[1].id").value(STL_A_APR_PAID))
                .andExpect(jsonPath("$.items[1].status").value("PAID"));
    }

    @Test
    @DisplayName("S2 상세·품목: 본인 CONFIRMED 200(계좌 끝 4자리·품목 건수)·PAID 200(스냅샷 계좌)")
    void detailAndItems_visible() throws Exception {
        mockMvc.perform(get(URL + "/" + STL_A_JUNE_CONFIRMED).headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(STL_A_JUNE_CONFIRMED))
                .andExpect(jsonPath("$.saleItemCount").value(1))
                .andExpect(jsonPath("$.refundItemCount").value(0))
                .andExpect(jsonPath("$.bankAccount.accountNumberSuffix").value("4321"))
                .andExpect(jsonPath("$.bankAccount.snapshot").value(false))
                .andExpect(jsonPath("$.seller").doesNotExist())
                .andExpect(jsonPath("$.sellerContact").doesNotExist());
        mockMvc.perform(get(URL + "/" + STL_A_APR_PAID).headers(authHeaders.seller(SELLER_A_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccount.snapshot").value(true));
        mockMvc.perform(get(URL + "/" + STL_A_JUNE_CONFIRMED + "/items").headers(authHeaders.seller(SELLER_A_USER))
                        .param("type", "SALE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productName").value("상품1"))
                .andExpect(jsonPath("$.items[0].feeAmount").value(1500));
    }

    @Test
    @DisplayName("S3 404 통일: 본인 PENDING·타 셀러 CONFIRMED·미존재 → 상세/품목 모두 404 SETTLEMENT_NOT_FOUND")
    void detailAndItems_hidden404() throws Exception {
        for (long id : new long[] {STL_A_MAY_PENDING, STL_B_JUNE_CONFIRMED, 999_999L}) {
            mockMvc.perform(get(URL + "/" + id).headers(authHeaders.seller(SELLER_A_USER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
            mockMvc.perform(get(URL + "/" + id + "/items").headers(authHeaders.seller(SELLER_A_USER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("S4 인가: BUYER 토큰 403·seller_user 미매핑 SELLER 토큰 401")
    void authorization() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL).headers(authHeaders.seller(UNMAPPED_USER)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- seed·cleanup(바인딩 파라미터·정적 SQL·SQL injection 위험 없음) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, "
                        + "updated_at) VALUES (?, ?, '셀러A', '대표', 'ACTIVE', NULL, NOW(6), NOW(6))", SELLER_A,
                        "slr_STL85SLRSELLERA00000000000");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, "
                        + "updated_at) VALUES (?, ?, '셀러B', '대표', 'ACTIVE', NULL, NOW(6), NOW(6))", SELLER_B,
                        "slr_STL85SLRSELLERB00000000000");
                // resolver가 user.id→seller.id를 seller_user로 해소하므로 실 매핑을 시드한다(user 행은 인증 필터가 부재를 통과시킴)
                jdbc.update("INSERT INTO seller_user (user_id, seller_id, role_id, created_at, updated_at) "
                        + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", SELLER_A_USER, SELLER_A);
                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, "
                        + "is_primary, status, created_at, updated_at) VALUES (?, ?, '004', ?, '대표', 1, "
                        + "'VERIFIED', NOW(6), NOW(6))", SELLER_A_ACCOUNT, SELLER_A, bankAccountEncryptor.encrypt("1234567890-4321"));
                insertSettlement(STL_A_JUNE_CONFIRMED, SELLER_A, 2026, 6, "CONFIRMED", null);
                insertSettlement(STL_A_MAY_PENDING, SELLER_A, 2026, 5, "PENDING", null);
                insertSettlement(STL_A_APR_PAID, SELLER_A, 2026, 4, "PAID", SELLER_A_ACCOUNT);
                insertSettlement(STL_B_JUNE_CONFIRMED, SELLER_B, 2026, 6, "CONFIRMED", null);
                jdbc.update("INSERT INTO settlement_item (id, settlement_id, item_type, order_item_id, refund_id, order_public_id, "
                        + "product_name, option_label, quantity, amount, commission_rate, fee_amount, occurred_at, created_at) "
                        + "VALUES (?, ?, 'SALE', 1, NULL, 'ord_STL85SLRORDER000000000000', '상품1', NULL, 1, 15000, 1000, 1500, ?, NOW(6))",
                        9495L, STL_A_JUNE_CONFIRMED, LocalDateTime.of(2026, 6, 10, 9, 0));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertSettlement(long id, long sellerId, int year, int month, String status, Long bankAccountId) {
        LocalDateTime start = LocalDateTime.of(year, month, 1, 0, 0);
        LocalDateTime end = start.toLocalDate().withDayOfMonth(start.toLocalDate().lengthOfMonth())
                .atTime(23, 59, 59, 999_999_000);
        jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, "
                + "fee_amount, refund_amount, net_amount, status, paid_at, scheduled_pay_date, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 15000, 1500, 0, 13500, ?, ?, ?, NOW(6), NOW(6))",
                id, sellerId, bankAccountId, start, end, status,
                "PAID".equals(status) ? LocalDateTime.of(2026, 5, 20, 10, 0) : null, end.toLocalDate().plusDays(20));
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM settlement_item WHERE id = ?", 9495L);
                jdbc.update("DELETE FROM settlement WHERE id IN (?, ?, ?, ?)", STL_A_JUNE_CONFIRMED, STL_A_MAY_PENDING,
                        STL_A_APR_PAID, STL_B_JUNE_CONFIRMED);
                jdbc.update("DELETE FROM seller_bank_account WHERE id = ?", SELLER_A_ACCOUNT);
                jdbc.update("DELETE FROM seller_user WHERE user_id = ?", SELLER_A_USER);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

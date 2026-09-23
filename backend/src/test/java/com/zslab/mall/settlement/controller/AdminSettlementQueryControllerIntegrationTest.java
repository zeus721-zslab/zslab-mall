package com.zslab.mall.settlement.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import com.zslab.mall.common.security.AuthHeaders;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
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
 * 관리자 정산 조회 API E2E 통합 테스트(Track 85·실 MariaDB). 목록(year/month 필수·status·keyword·합계·enrich)·상세(연락처 마스킹·계좌
 * 스냅샷 우선·끝 4자리)·품목(type·페이징·정렬)·셀러 이력(404)·403을 검증한다.
 *
 * <p><b>시드</b>: 셀러 A(연락처·주 계좌 있음) 6월 PENDING(SALE 2·REFUND 1) + 5월 PAID(계좌 스냅샷) / 셀러 B(계좌 없음) 6월 CONFIRMED.
 * 조회 전용이라 FK_CHECKS=0 하에 직접 INSERT하고 실 커밋한다(클래스 @Transactional 없음·시드/정리는 TransactionTemplate).
 */
@AutoConfigureMockMvc
class AdminSettlementQueryControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9490L;
    private static final long BUYER_ID = 9491L;
    private static final long SELLER_A = 9490L;
    private static final long SELLER_B = 9491L;
    private static final long SELLER_A_ACCOUNT = 9490L;
    private static final long SELLER_A_OLD_ACCOUNT = 9492L;
    private static final long STL_A_JUNE = 9490L;
    private static final long STL_A_MAY = 9491L;
    private static final long STL_B_JUNE = 9492L;
    private static final String SELLER_A_PID = "slr_STL85QRYSELLERA00000000000";
    private static final String SELLER_B_PID = "slr_STL85QRYSELLERB00000000000";
    private static final String ORDER_PID = "ord_STL85QRYORDER000000000000";
    private static final LocalDateTime JUNE_START = LocalDateTime.of(2026, 6, 1, 0, 0);
    private static final LocalDateTime JUNE_END = LocalDateTime.of(2026, 6, 30, 23, 59, 59, 999_999_000);
    private static final LocalDateTime MAY_START = LocalDateTime.of(2026, 5, 1, 0, 0);
    private static final LocalDateTime MAY_END = LocalDateTime.of(2026, 5, 31, 23, 59, 59, 999_999_000);
    private static final String URL = "/api/v1/admin/settlements";

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
    @DisplayName("Q1 목록: year/month 누락 → 400 MALFORMED_REQUEST·month 13 → 400 SETTLEMENT_PERIOD_INVALID·BUYER 403")
    void list_validation() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("year", "2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("year", "2026").param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_PERIOD_INVALID"));
        mockMvc.perform(get(URL).headers(authHeaders.buyer(BUYER_ID)).param("year", "2026").param("month", "6"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Q2 목록: 6월 2건(sellerId 순)·셀러 publicId/상호·계좌 유무·SALE 건수·월 합계(상태별 건수·금액)")
    void list_juneRowsAndTotals() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID)).param("year", "2026").param("month", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].id").value(STL_A_JUNE))
                .andExpect(jsonPath("$.items[0].seller.publicId").value(SELLER_A_PID))
                .andExpect(jsonPath("$.items[0].seller.companyName").value("정산셀러A"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].grossAmount").value(15000))
                .andExpect(jsonPath("$.items[0].feeAmount").value(1500))
                .andExpect(jsonPath("$.items[0].refundAmount").value(3000))
                .andExpect(jsonPath("$.items[0].netAmount").value(10500))
                .andExpect(jsonPath("$.items[0].scheduledPayDate").value("2026-07-20"))
                .andExpect(jsonPath("$.items[0].bankAccountRegistered").value(true))
                .andExpect(jsonPath("$.items[0].saleItemCount").value(2))
                .andExpect(jsonPath("$.items[1].id").value(STL_B_JUNE))
                .andExpect(jsonPath("$.items[1].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[1].bankAccountRegistered").value(false))
                .andExpect(jsonPath("$.items[1].saleItemCount").value(0))
                .andExpect(jsonPath("$.totals.grossAmount").value(35000))
                .andExpect(jsonPath("$.totals.feeAmount").value(3500))
                .andExpect(jsonPath("$.totals.refundAmount").value(3000))
                .andExpect(jsonPath("$.totals.netAmount").value(28500))
                .andExpect(jsonPath("$.totals.pendingCount").value(1))
                .andExpect(jsonPath("$.totals.confirmedCount").value(1))
                .andExpect(jsonPath("$.totals.paidCount").value(0));
    }

    @Test
    @DisplayName("Q3 목록 필터: status=CONFIRMED 1건(합계는 월 전체 유지)·keyword 상호 부분일치 1건·불일치 0건·%·_ 리터럴 매칭(ESCAPE)")
    void list_filters() throws Exception {
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID))
                        .param("year", "2026").param("month", "6").param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(STL_B_JUNE))
                .andExpect(jsonPath("$.totals.pendingCount").value(1));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID))
                        .param("year", "2026").param("month", "6").param("keyword", "셀러B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].seller.publicId").value(SELLER_B_PID));
        mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID))
                        .param("year", "2026").param("month", "6").param("keyword", "없는셀러"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalCount").value(0));
        // LIKE 와일드카드 이스케이프: "%"·"_"는 전체(2건)가 아니라 상호에 해당 문자를 가진 셀러B만 매칭
        for (String wildcard : List.of("%", "_")) {
            mockMvc.perform(get(URL).headers(authHeaders.admin(ADMIN_ID))
                            .param("year", "2026").param("month", "6").param("keyword", wildcard))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].seller.publicId").value(SELLER_B_PID));
        }
    }

    @Test
    @DisplayName("Q4 상세: 연락처 마스킹·현재 주 계좌(snapshot=false·끝 4자리)·품목 건수 / PAID는 계좌 스냅샷 우선 / 계좌 없는 셀러 null / 미존재 404")
    void detail() throws Exception {
        mockMvc.perform(get(URL + "/" + STL_A_JUNE).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(STL_A_JUNE))
                .andExpect(jsonPath("$.seller.publicId").value(SELLER_A_PID))
                .andExpect(jsonPath("$.sellerContact.contactPhone").value("010-****-5678"))
                .andExpect(jsonPath("$.sellerContact.contactEmail").value("se***@example.com"))
                .andExpect(jsonPath("$.saleItemCount").value(2))
                .andExpect(jsonPath("$.refundItemCount").value(1))
                .andExpect(jsonPath("$.bankAccount.id").value(SELLER_A_ACCOUNT))
                .andExpect(jsonPath("$.bankAccount.bankCode").value("004"))
                .andExpect(jsonPath("$.bankAccount.accountHolder").value("대표A"))
                .andExpect(jsonPath("$.bankAccount.accountNumberSuffix").value("4321"))
                .andExpect(jsonPath("$.bankAccount.snapshot").value(false));
        mockMvc.perform(get(URL + "/" + STL_A_MAY).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.bankAccount.id").value(SELLER_A_OLD_ACCOUNT))
                .andExpect(jsonPath("$.bankAccount.accountNumberSuffix").value("9999"))
                .andExpect(jsonPath("$.bankAccount.snapshot").value(true));
        mockMvc.perform(get(URL + "/" + STL_B_JUNE).headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccount").doesNotExist())
                .andExpect(jsonPath("$.sellerContact.contactPhone").doesNotExist());
        mockMvc.perform(get(URL + "/999999").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Q5 품목: 전체 3건 occurred_at 오름차순 / type=SALE size=1 → totalCount 2·hasNext / type=REFUND 1건 / 미존재 404")
    void items() throws Exception {
        mockMvc.perform(get(URL + "/" + STL_A_JUNE + "/items").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].itemType").value("SALE"))
                .andExpect(jsonPath("$.items[0].productName").value("상품1"))
                .andExpect(jsonPath("$.items[0].orderPublicId").value(ORDER_PID))
                .andExpect(jsonPath("$.items[0].commissionRate").value(1000))
                .andExpect(jsonPath("$.items[0].feeAmount").value(1000))
                .andExpect(jsonPath("$.items[1].productName").value("상품2"))
                .andExpect(jsonPath("$.items[2].itemType").value("REFUND"))
                .andExpect(jsonPath("$.items[2].refundId").value(9490))
                .andExpect(jsonPath("$.items[2].feeAmount").value(0));
        mockMvc.perform(get(URL + "/" + STL_A_JUNE + "/items").headers(authHeaders.admin(ADMIN_ID))
                        .param("type", "SALE").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get(URL + "/" + STL_A_JUNE + "/items").headers(authHeaders.admin(ADMIN_ID))
                        .param("type", "REFUND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].amount").value(3000));
        mockMvc.perform(get(URL + "/999999/items").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Q6 셀러 이력: 최신 기간순 2건(6월·5월) / 미존재 셀러 404 SELLER_NOT_FOUND")
    void sellerHistory() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sellers/" + SELLER_A_PID + "/settlements").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(STL_A_JUNE))
                .andExpect(jsonPath("$.items[1].id").value(STL_A_MAY))
                .andExpect(jsonPath("$.items[1].status").value("PAID"));
        mockMvc.perform(get("/api/v1/admin/sellers/slr_NOPE00000000000000000000000/settlements")
                        .headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));
    }

    // ---------- seed·cleanup(바인딩 파라미터·정적 SQL·SQL injection 위험 없음) ----------

    private void seed() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, contact_email, contact_phone, status, "
                        + "commission_rate, created_at, updated_at) VALUES (?, ?, '정산셀러A', '대표', 'sellerA@example.com', "
                        + "'010-1234-5678', 'ACTIVE', NULL, NOW(6), NOW(6))", SELLER_A, SELLER_A_PID);
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, "
                        + "updated_at) VALUES (?, ?, '정산셀러B%_', '대표', 'ACTIVE', NULL, NOW(6), NOW(6))", SELLER_B, SELLER_B_PID);
                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, "
                        + "is_primary, status, created_at, updated_at) VALUES (?, ?, '004', ?, '대표A', 1, "
                        + "'VERIFIED', NOW(6), NOW(6))", SELLER_A_ACCOUNT, SELLER_A, bankAccountEncryptor.encrypt("1234567890-4321"));
                // 5월 지급 당시 계좌(현재는 주 계좌 아님) — 스냅샷 우선 검증
                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, "
                        + "is_primary, status, created_at, updated_at) VALUES (?, ?, '088', ?, '대표A', 0, "
                        + "'VERIFIED', NOW(6), NOW(6))", SELLER_A_OLD_ACCOUNT, SELLER_A, bankAccountEncryptor.encrypt("000-9999"));
                insertSettlement(STL_A_JUNE, SELLER_A, JUNE_START, JUNE_END, 15000, 1500, 3000, "PENDING", null, null);
                insertSettlement(STL_A_MAY, SELLER_A, MAY_START, MAY_END, 20000, 2000, 0, "PAID", SELLER_A_OLD_ACCOUNT,
                        LocalDateTime.of(2026, 6, 20, 10, 0));
                insertSettlement(STL_B_JUNE, SELLER_B, JUNE_START, JUNE_END, 20000, 2000, 0, "CONFIRMED", null, null);
                insertItem(9490L, STL_A_JUNE, "SALE", 9490L, null, "상품1", 10000, 1000, LocalDateTime.of(2026, 6, 10, 9, 0));
                insertItem(9491L, STL_A_JUNE, "SALE", 9491L, null, "상품2", 5000, 500, LocalDateTime.of(2026, 6, 15, 9, 0));
                insertItem(9492L, STL_A_JUNE, "REFUND", 9492L, 9490L, "상품3", 3000, 0, LocalDateTime.of(2026, 6, 20, 9, 0));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertSettlement(long id, long sellerId, LocalDateTime start, LocalDateTime end, long gross, long fee,
            long refund, String status, Long bankAccountId, LocalDateTime paidAt) {
        jdbc.update("INSERT INTO settlement (id, seller_id, bank_account_id, period_start, period_end, gross_amount, "
                + "fee_amount, refund_amount, net_amount, status, paid_at, scheduled_pay_date, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))",
                id, sellerId, bankAccountId, start, end, gross, fee, refund, gross - fee - refund, status, paidAt,
                end.toLocalDate().plusDays(20));
    }

    private void insertItem(long id, long settlementId, String type, long orderItemId, Long refundId, String productName,
            long amount, long fee, LocalDateTime occurredAt) {
        // source_id(V37 CHECK 필수) = SALE은 order_item_id·REFUND는 refund_id(SettlementItem 팩토리와 같은 규칙).
        jdbc.update("INSERT INTO settlement_item (id, settlement_id, item_type, order_item_id, refund_id, source_id, order_public_id, "
                + "product_name, option_label, quantity, amount, commission_rate, fee_amount, occurred_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?, 1000, ?, ?, NOW(6))",
                id, settlementId, type, orderItemId, refundId, refundId != null ? refundId : orderItemId, ORDER_PID, productName,
                amount, fee, occurredAt);
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM settlement_item WHERE settlement_id IN (?, ?, ?)", STL_A_JUNE, STL_A_MAY, STL_B_JUNE);
                jdbc.update("DELETE FROM settlement WHERE id IN (?, ?, ?)", STL_A_JUNE, STL_A_MAY, STL_B_JUNE);
                jdbc.update("DELETE FROM seller_bank_account WHERE id IN (?, ?)", SELLER_A_ACCOUNT, SELLER_A_OLD_ACCOUNT);
                jdbc.update("DELETE FROM seller WHERE id IN (?, ?)", SELLER_A, SELLER_B);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }
}

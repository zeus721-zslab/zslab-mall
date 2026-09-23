package com.zslab.mall.settlement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.common.security.AuthHeaders;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zslab.mall.support.AbstractIntegrationTest;

/**
 * Admin 월 정산 배치 endpoint E2E 통합 테스트(Track 48 P3·실 MariaDB). HTTP → {@code AdminSettlementController} →
 * {@code SettlementCreationService} → 집계·병합·생성 → DB 실 커밋을 검증한다({@code BuyerOrderConfirmControllerIntegrationTest} 패턴 1:1).
 *
 * <p><b>커버</b>: T1 201(gross 집계·fee·net·periodEnd .999999 경계 포함·품목 스냅샷 2건)·T2 403(비ADMIN)·T3 400(month 13)·T4 201
 * 멱등(재실행 0건)·T5 재생성 200(PENDING·새 id·감사 DELETE+CREATE)·T6 재생성 422(CONFIRMED)·T7 재생성 400(reason 누락).
 *
 * <p><b>트랜잭션</b>: 실 커밋으로 정산 생성을 구동하므로 클래스 {@code @Transactional} 없음. 시드/정리는 {@link TransactionTemplate}
 * + {@code FOREIGN_KEY_CHECKS=0}(try-finally), 검증은 {@link JdbcTemplate}. 시각은 {@code LocalDateTime} 바인딩으로 시드해
 * 서비스 조회 파라미터와 세션TZ 오프셋을 상쇄한다(P2 트랩 대응).
 */
@AutoConfigureMockMvc
class AdminSettlementControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 9480L;         // JWT 액터(created_by 미사용·DB 행 불요)
    private static final long BUYER_ID = 9481L;         // 비ADMIN 403 확인용
    private static final long SELLER_ID = 9480L;
    private static final long BANK_ACCOUNT_ID = 9480L;
    private static final long ORDER_ID = 9480L;
    private static final long ITEM_INTERIOR_ID = 9480L; // 기간 내 정각
    private static final long ITEM_BOUNDARY_ID = 9481L;  // 말일 23:59:59.999999 — periodEnd 경계 포함 검증
    private static final String URL = "/api/v1/admin/settlements";
    private static final String BODY_JUNE = "{\"year\":2026,\"month\":6}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthHeaders authHeaders;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate tx;
    private long settlementIdBefore;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        settlementIdBefore = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM settlement", Long.class);
    }

    @AfterEach
    void tearDown() {
        cleanupCreatedSettlements();
        cleanup();
    }

    @Test
    @DisplayName("T1 정상: ADMIN + 기간 내 CONFIRMED(periodEnd .999999 경계 포함) → 201·gross/fee/net 정확")
    void create_admin_returns201() throws Exception {
        seedSellerWithSales();

        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_JUNE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.settlements[0].sellerId").value(SELLER_ID))
                .andExpect(jsonPath("$.settlements[0].grossAmount").value(15000))  // 10000 + 5000(경계 포함)
                .andExpect(jsonPath("$.settlements[0].feeAmount").value(1500))     // 15000*1000/10000
                .andExpect(jsonPath("$.settlements[0].netAmount").value(13500))
                .andExpect(jsonPath("$.settlements[0].scheduledPayDate").value("2026-07-20"))
                .andExpect(jsonPath("$.settlements[0].commissionRate").doesNotExist());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE seller_id = ?", Integer.class, SELLER_ID);
        assertThat(count).isEqualTo(1);
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_item si JOIN settlement s ON s.id = si.settlement_id WHERE s.seller_id = ?",
                Integer.class, SELLER_ID);
        assertThat(itemCount).isEqualTo(2);
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'SETTLEMENT' AND action = 'CREATE' AND target_id = "
                        + "(SELECT id FROM settlement WHERE seller_id = ?)", Integer.class, SELLER_ID);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("T5 재생성: PENDING → 200·새 id·품목 재적재·감사 DELETE(reason)+CREATE")
    void regenerate_pending_returns200() throws Exception {
        seedSellerWithSales();
        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_JUNE))
                .andExpect(status().isCreated());
        Long originalId = jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ?", Long.class, SELLER_ID);

        mockMvc.perform(post(URL + "/" + originalId + "/regenerate").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"검수 정정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedSettlementId").value(originalId))
                .andExpect(jsonPath("$.deletedOnly").value(false))
                .andExpect(jsonPath("$.grossAmount").value(15000))
                .andExpect(jsonPath("$.netAmount").value(13500));

        Long newId = jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ?", Long.class, SELLER_ID);
        assertThat(newId).isNotEqualTo(originalId);
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_item WHERE settlement_id = ?", Integer.class, newId);
        assertThat(itemCount).isEqualTo(2);
        Integer deleteAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'SETTLEMENT' AND action = 'DELETE' AND target_id = ? "
                        + "AND diff_json LIKE '%검수 정정%'", Integer.class, originalId);
        assertThat(deleteAudit).isEqualTo(1);
    }

    @Test
    @DisplayName("T6 재생성: CONFIRMED → 422 SETTLEMENT_INVALID_STATE")
    void regenerate_confirmed_returns422() throws Exception {
        seedSellerWithSales();
        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_JUNE))
                .andExpect(status().isCreated());
        Long id = jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ?", Long.class, SELLER_ID);
        mockMvc.perform(post(URL + "/" + id + "/confirm").headers(authHeaders.admin(ADMIN_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(post(URL + "/" + id + "/regenerate").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"검수 정정\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_INVALID_STATE"));
    }

    @Test
    @DisplayName("T7 재생성: reason 누락(blank) → 400·정산 유지")
    void regenerate_missingReason_returns400() throws Exception {
        seedSellerWithSales();
        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_JUNE))
                .andExpect(status().isCreated());
        Long id = jdbc.queryForObject("SELECT id FROM settlement WHERE seller_id = ?", Long.class, SELLER_ID);

        mockMvc.perform(post(URL + "/" + id + "/regenerate").headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 비ADMIN: BUYER 토큰 → 403")
    void create_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post(URL).headers(authHeaders.buyer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_JUNE))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("T3 잘못된 month(13) → 400 SETTLEMENT_PERIOD_INVALID")
    void create_invalidMonth_returns400() throws Exception {
        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2026,\"month\":13}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_PERIOD_INVALID"));
    }

    @Test
    @DisplayName("T3b 진행 중 월(오늘 포함) → 400 SETTLEMENT_PERIOD_INVALID(마감 전 생성 차단·D-168 보충)")
    void create_currentMonth_returns400() throws Exception {
        YearMonth current = YearMonth.now();
        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":" + current.getYear() + ",\"month\":" + current.getMonthValue() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_PERIOD_INVALID"));
    }

    @Test
    @DisplayName("T4 멱등: 재실행 → 201·createdCount 0·중복 미생성")
    void create_rerun_isIdempotent() throws Exception {
        seedSellerWithSales();

        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_JUNE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1));

        mockMvc.perform(post(URL).headers(authHeaders.admin(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_JUNE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(0));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE seller_id = ?", Integer.class, SELLER_ID);
        assertThat(count).isEqualTo(1);
    }

    // ---------- seed·helpers ----------
    // 모든 시드 INSERT는 바인딩 파라미터 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).
    // ⚠️confirmed_at은 EntityManager(Hibernate) 바인딩으로 넣는다 — JdbcTemplate(raw JDBC)는 LocalDateTime을 드라이버
    // 세션TZ로 처리하나 서비스 집계 쿼리는 Hibernate 바인딩이라, 두 레이어가 어긋나면 말일 경계 항목이 기간 밖으로 밀린다.
    // 프로덕션은 confirmed_at 기록·집계 모두 Hibernate라 일관(정합). 테스트도 집계와 같은 Hibernate 경로로 시드해 상쇄한다.

    private void seedSellerWithSales() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, "
                                + "created_at, updated_at) VALUES (?, ?, '정산셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "STLSLR"));
                jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, "
                                + "is_primary, status, created_at, updated_at) "
                                + "VALUES (?, ?, '004', '123', '대표', 1, 'VERIFIED', NOW(6), NOW(6))",
                        BANK_ACCOUNT_ID, SELLER_ID);
                // 품목 스냅샷이 주문 public_id를 JOIN oi.order로 읽으므로 order 부모를 시드한다(Track 85)
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                                + "shipping_fee, created_at, updated_at) VALUES (?, ?, 1, ?, 'CONFIRMED', 15000, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "STLORD"), "STL-" + ORDER_ID);
                // 기간 내 정각(10000) + periodEnd .999999 경계 항목(5000·말일 23:59:59.999999) → gross 15000
                insertConfirmedOrderItemViaHibernate(
                        ITEM_INTERIOR_ID, pid("oit_", "STLOI1"), 10_000L, LocalDateTime.of(2026, 6, 15, 12, 0, 0));
                insertConfirmedOrderItemViaHibernate(
                        ITEM_BOUNDARY_ID, pid("oit_", "STLOI2"), 5_000L,
                        LocalDateTime.of(2026, 6, 30, 23, 59, 59, 999_999_000));
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void insertConfirmedOrderItemViaHibernate(long id, String publicId, long price, LocalDateTime confirmedAt) {
        entityManager.createNativeQuery(
                "INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, "
                + "unit_price, total_price, commission_rate, item_status, confirmed_at, created_at, updated_at, product_name) "
                + "VALUES (?1, ?2, ?6, 1, 1, ?3, 1, ?4, ?4, 1000, 'CONFIRMED', ?5, NOW(6), NOW(6), '테스트 상품')")
            .setParameter(1, id)
            .setParameter(2, publicId)
            .setParameter(3, SELLER_ID)
            .setParameter(4, price)
            .setParameter(5, confirmedAt)
            .setParameter(6, ORDER_ID)
            .executeUpdate();
    }

    /**
     * 이 테스트 중 만들어진 정산을 셀러와 무관하게 지운다(Track 104-3b). 생성은 전 셀러 대상이고 기간 하한이 없어 다른 테스트의 미정산 잔여
     * 사실까지 편입할 수 있다 — 남기면 그 품목의 settlement_item 출처 키가 뒤 테스트의 편입을 막는다. 모든 변수는 ? 바인딩이다.
     */
    private void cleanupCreatedSettlements() {
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'SETTLEMENT' AND target_id > ?", settlementIdBefore);
            jdbc.update("DELETE FROM settlement_item WHERE settlement_id > ?", settlementIdBefore);
            jdbc.update("DELETE FROM settlement WHERE id > ?", settlementIdBefore);
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'SETTLEMENT' AND target_id IN "
                        + "(SELECT id FROM settlement WHERE seller_id = ?)", SELLER_ID);
                jdbc.update("DELETE FROM settlement_item WHERE settlement_id IN (SELECT id FROM settlement WHERE seller_id = ?)",
                        SELLER_ID);
                jdbc.update("DELETE FROM settlement WHERE seller_id = ?", SELLER_ID);
                jdbc.update("DELETE FROM order_item WHERE id IN (?, ?)", ITEM_INTERIOR_ID, ITEM_BOUNDARY_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
                jdbc.update("DELETE FROM seller_bank_account WHERE id = ?", BANK_ACCOUNT_ID);
                jdbc.update("DELETE FROM seller WHERE id = ?", SELLER_ID);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

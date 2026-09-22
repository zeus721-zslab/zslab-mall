package com.zslab.mall.settlement.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.settlement.service.SettlementCreationService;
import com.zslab.mall.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link SettlementMonthlyCreationScheduler} 통합 테스트(Track 96-6·실 MariaDB). 스케줄러 진입점 → {@code SettlementCreationService}
 * → 전월 PENDING 생성·시스템 행위자 감사(actor_user_id NULL·actor_role SYSTEM)·재실행 멱등 skip을 실 커밋으로 검증한다.
 *
 * <p><b>스케줄러 자동 발화 차단</b>: {@code AbstractIntegrationTest}가 킬스위치를 전역으로 내리므로 빈이 없고(T3 검증 겸), 배치는
 * 스케줄러를 직접 생성해 호출한다(OrderAutoConfirmIntegrationTest 패턴). 전월은 실행일 상대값이라 시드도 {@link YearMonth#now()} 기준
 * 전월 15일로 넣는다. confirmed_at은 서비스 집계와 같은 Hibernate 바인딩 경로로 시드한다(AdminSettlementControllerIntegrationTest 주석).
 */
class SettlementMonthlyCreationSchedulerIntegrationTest extends AbstractIntegrationTest {

    private static final long SELLER_ID = 9690L;
    private static final long ORDER_ID = 9690L;
    private static final long ITEM_ID = 9690L;

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private SettlementCreationService settlementCreationService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate tx;
    private SettlementMonthlyCreationScheduler scheduler;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        scheduler = new SettlementMonthlyCreationScheduler(settlementCreationService);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("T1 전월 자동 생성: 스케줄러 진입점 → 전월 PENDING 1건·감사 CREATE actor_user_id NULL·actor_role SYSTEM")
    void createPreviousMonthBatch_createsPendingWithSystemActor() {
        YearMonth previousMonth = YearMonth.now().minusMonths(1);
        seedSellerWithSale(previousMonth.atDay(15).atTime(12, 0));

        scheduler.createPreviousMonthBatch();

        Map<String, Object> settlement = jdbc.queryForMap(
                "SELECT status, period_start, gross_amount, fee_amount, net_amount FROM settlement WHERE seller_id = ?", SELLER_ID);
        assertThat(settlement.get("status")).isEqualTo("PENDING");
        assertThat(((java.sql.Timestamp) settlement.get("period_start")).toLocalDateTime())
                .isEqualTo(previousMonth.atDay(1).atStartOfDay());
        assertThat(((Number) settlement.get("gross_amount")).longValue()).isEqualTo(10_000L);
        assertThat(((Number) settlement.get("fee_amount")).longValue()).isEqualTo(1_000L);
        assertThat(((Number) settlement.get("net_amount")).longValue()).isEqualTo(9_000L);

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, actor_role FROM audit_log WHERE target_type = 'SETTLEMENT' AND action = 'CREATE' "
                        + "AND target_id = (SELECT id FROM settlement WHERE seller_id = ?)", SELLER_ID);
        assertThat(audit.get("actor_user_id")).isNull();
        assertThat(audit.get("actor_role")).isEqualTo(AuditContext.SYSTEM_ACTOR_ROLE);
    }

    @Test
    @DisplayName("T2 재실행 멱등: 같은 전월 2회 실행 → 정산 1건·감사 CREATE 1건(기존 skip)")
    void createPreviousMonthBatch_rerunSkips() {
        seedSellerWithSale(YearMonth.now().minusMonths(1).atDay(15).atTime(12, 0));

        scheduler.createPreviousMonthBatch();
        scheduler.createPreviousMonthBatch();

        assertThat(settlementCount()).isEqualTo(1);
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'SETTLEMENT' AND action = 'CREATE' "
                        + "AND target_id = (SELECT id FROM settlement WHERE seller_id = ?)", Integer.class, SELLER_ID);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("T3 킬스위치: enabled=false 컨텍스트에 스케줄러 빈 없음 / 실행일 기준 전월만 대상(2개월 전 매출은 미생성)")
    void killSwitch_andOnlyPreviousMonth() {
        assertThat(applicationContext.getBeanNamesForType(SettlementMonthlyCreationScheduler.class)).isEmpty();

        seedSellerWithSale(YearMonth.now().minusMonths(2).atDay(15).atTime(12, 0));

        scheduler.createForPreviousMonthOf(LocalDate.now());

        assertThat(settlementCount()).isZero();
    }

    // ---------- seed·helpers (바인딩 파라미터 + 정적 SQL·SQL injection 위험 없음) ----------

    private void seedSellerWithSale(LocalDateTime confirmedAt) {
        tx.executeWithoutResult(s -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, "
                                + "created_at, updated_at) VALUES (?, ?, '정산셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))",
                        SELLER_ID, pid("slr_", "STLSCH"));
                jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, "
                                + "shipping_fee, created_at, updated_at) VALUES (?, ?, 1, ?, 'CONFIRMED', 10000, 0, 0, NOW(6), NOW(6))",
                        ORDER_ID, pid("ord_", "STLSCH"), "STLSCH-" + ORDER_ID);
                entityManager.createNativeQuery(
                        "INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, "
                        + "unit_price, total_price, commission_rate, item_status, confirmed_at, created_at, updated_at, product_name) "
                        + "VALUES (?1, ?2, ?3, 1, 1, ?4, 1, 10000, 10000, 1000, 'CONFIRMED', ?5, NOW(6), NOW(6), '테스트 상품')")
                        .setParameter(1, ITEM_ID)
                        .setParameter(2, pid("oit_", "STLSCH"))
                        .setParameter(3, ORDER_ID)
                        .setParameter(4, SELLER_ID)
                        .setParameter(5, confirmedAt)
                        .executeUpdate();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private int settlementCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE seller_id = ?", Integer.class, SELLER_ID);
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
                jdbc.update("DELETE FROM order_item WHERE id = ?", ITEM_ID);
                jdbc.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
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

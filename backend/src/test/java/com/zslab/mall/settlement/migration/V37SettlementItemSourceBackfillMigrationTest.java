package com.zslab.mall.settlement.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import com.zslab.mall.seller.migration.V33__Encrypt_seller_bank_account_numbers;
import com.zslab.mall.support.MariaDbTestContainer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V37 정산 품목 출처 키 backfill 검증(Track 104-3b·V34SaleStopSourceBackfillMigrationTest 패턴). 별도 스키마를 V36까지 마이그레이션 →
 * 두 정산에 SALE·REFUND 품목을 시딩 → V37 적용 → source_id 백필(SALE=order_item_id·REFUND=refund_id)·(item_type, source_id) 전역 UNIQUE·
 * 이월 행(주문 품목 없음) 적재 가능·기존 정산 carryover_amount 0을 확인한다. 통합 테스트 컨텍스트는 빈 스키마에 전체 마이그레이션을 한 번에 적용하므로 backfill 경로가
 * 실행되지 않는다(본 테스트가 유일한 커버).
 */
class V37SettlementItemSourceBackfillMigrationTest {

    private static final String SCHEMA = "track104_3b_v37_backfill";
    private static final String ROOT_USER = "root";
    private static final byte[] TEST_KEY = "track104-3b-test-key-0123456789a".getBytes(StandardCharsets.UTF_8);

    private String schemaUrl;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() throws SQLException {
        String baseUrl = MariaDbTestContainer.INSTANCE.getJdbcUrl();
        String password = MariaDbTestContainer.INSTANCE.getPassword();
        try (Connection connection = DriverManager.getConnection(baseUrl, ROOT_USER, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        schemaUrl = baseUrl.replaceFirst("/" + MariaDbTestContainer.INSTANCE.getDatabaseName() + "(\\?|$)", "/" + SCHEMA + "$1");
        dataSource = new SingleConnectionDataSource(schemaUrl, ROOT_USER, password, true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void dropSchema() throws SQLException {
        dataSource.destroy();
        try (Connection connection = DriverManager.getConnection(
                        MariaDbTestContainer.INSTANCE.getJdbcUrl(), ROOT_USER, MariaDbTestContainer.INSTANCE.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    @Test
    @DisplayName("V36 상태의 SALE·REFUND 품목 → V37 적용 후 source_id 백필·(item_type, source_id) 전역 UNIQUE·이월 행 적재 가능·헤더 이월 0")
    void v37_backfillsSourceIdAndEnforcesGlobalUnique() {
        migrateTo("36");
        // FK 부모 그래프 없이 시딩(세션 단일 커넥션이라 FOREIGN_KEY_CHECKS=0 유지). 모든 값은 ? 바인딩.
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        seedSettlement(1, "2026-07-01 00:00:00", "2026-07-31 23:59:59.999999");
        seedSettlement(2, "2026-08-01 00:00:00", "2026-08-31 23:59:59.999999");
        seedItem(1, "SALE", 101L, null);
        seedItem(1, "SALE", 102L, null);
        seedItem(2, "SALE", 103L, null);
        seedItem(2, "REFUND", 101L, 501L);

        migrateTo("37");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_item WHERE item_type = 'SALE' AND source_id = order_item_id",
                Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT source_id FROM settlement_item WHERE item_type = 'REFUND'", Long.class))
                .as("REFUND 출처 = refund_id(order_item_id 아님)").isEqualTo(501L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_item WHERE source_id IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT column_type FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'settlement_item' AND column_name = 'item_type'", String.class, SCHEMA))
                .isEqualTo("enum('SALE','REFUND','CARRYOVER')");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE carryover_amount = 0", Long.class))
                .as("기존 정산은 이월 없음(DEFAULT 0)").isEqualTo(2L);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement_item (settlement_id, item_type, order_item_id, source_id, "
                + "order_public_id, product_name, quantity, amount, commission_rate, fee_amount, occurred_at, created_at) "
                + "VALUES (2, 'SALE', 101, 101, 'ord_V3700000000000000000000000', 'V37 상품', 1, 1000, 1000, 100, "
                + "'2026-07-15 12:00:00', NOW(6))"))
                .as("같은 매출 품목은 다른 정산에도 다시 들어갈 수 없다(전역 UNIQUE)")
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO settlement_item (settlement_id, item_type, source_id, amount, commission_rate, fee_amount, occurred_at, "
                + "created_at) VALUES (2, 'CARRYOVER', 1, 5000, 0, 0, '2026-07-31 23:59:59.999999', NOW(6))");
        assertThat(jdbc.queryForObject("SELECT dedup_key IS NULL FROM settlement_item WHERE item_type = 'CARRYOVER'", Boolean.class))
                .as("이월 행은 order_item_id가 없어 정산 단위 dedup_key가 NULL").isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement_item (settlement_id, item_type, source_id, amount, commission_rate, "
                + "fee_amount, occurred_at, created_at) VALUES (1, 'CARRYOVER', 1, 5000, 0, 0, '2026-07-31 23:59:59.999999', NOW(6))"))
                .as("같은 원 정산의 이월은 한 번만")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("37");
    }

    private void seedSettlement(long id, String periodStart, String periodEnd) {
        jdbc.update("INSERT INTO settlement (id, seller_id, period_start, period_end, gross_amount, fee_amount, refund_amount, net_amount, "
                + "status, created_at, updated_at) VALUES (?, 1, ?, ?, 0, 0, 0, 0, 'PENDING', NOW(6), NOW(6))", id, periodStart, periodEnd);
    }

    private void seedItem(long settlementId, String itemType, Long orderItemId, Long refundId) {
        jdbc.update("INSERT INTO settlement_item (settlement_id, item_type, order_item_id, refund_id, order_public_id, product_name, "
                + "quantity, amount, commission_rate, fee_amount, occurred_at, created_at) "
                + "VALUES (?, ?, ?, ?, 'ord_V3700000000000000000000000', 'V37 상품', 1, 1000, 1000, 100, '2026-07-15 12:00:00', NOW(6))",
                settlementId, itemType, orderItemId, refundId);
    }

    private void migrateTo(String targetVersion) {
        Flyway.configure()
                .dataSource(schemaUrl, ROOT_USER, MariaDbTestContainer.INSTANCE.getPassword())
                .locations("classpath:db/migration")
                .javaMigrations(new V33__Encrypt_seller_bank_account_numbers(new AesGcmTextEncryptor(TEST_KEY)))
                .target(targetVersion)
                .load()
                .migrate();
    }
}

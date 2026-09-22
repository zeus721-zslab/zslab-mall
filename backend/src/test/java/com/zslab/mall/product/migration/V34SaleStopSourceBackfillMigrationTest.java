package com.zslab.mall.product.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V34 판매중지 주체 backfill 검증(Track 96-5·D-206·V30CommissionRateBackfillMigrationTest 패턴). 별도 스키마를 V33까지 마이그레이션 →
 * STOPPED·SALE·PENDING·삭제된 STOPPED 상품을 시딩 → V34 적용 → STOPPED 전건 'ADMIN'(삭제 행 포함·fail-closed)·그 외 NULL·컬럼 ENUM 정의를
 * 확인한다. 통합 테스트 컨텍스트는 빈 스키마에 전체 마이그레이션을 한 번에 적용하므로 backfill 경로가 실행되지 않는다(본 테스트가 유일한 커버).
 * V33은 Spring 빈 Java 마이그레이션이라 standalone Flyway에는 {@code javaMigrations(...)}로 직접 등록한다.
 */
class V34SaleStopSourceBackfillMigrationTest {

    private static final String SCHEMA = "track96_5_v34_backfill";
    private static final String ROOT_USER = "root";
    private static final byte[] TEST_KEY = "track96-5-test-key-0123456789abc".getBytes(StandardCharsets.UTF_8);

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
    @DisplayName("V33 상태의 기존 product → V34 적용 후 STOPPED 전건(삭제 행 포함) sale_stop_source=ADMIN·그 외 NULL·컬럼 ENUM('ADMIN','SELLER') NULL 허용")
    void v34_backfillsStoppedAsAdmin() {
        migrateTo("33");
        // FK 부모 그래프 없이 시딩(세션 단일 커넥션이라 FOREIGN_KEY_CHECKS=0 유지). 모든 값은 ? 바인딩.
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        seedProduct(1, "V34STOPPED", "STOPPED", null);
        seedProduct(2, "V34SALE", "SALE", null);
        seedProduct(3, "V34PENDING", "PENDING", null);
        seedProduct(4, "V34REJECTED", "REJECTED", null);
        seedProduct(5, "V34STOPDEL", "STOPPED", "2026-09-01 09:00:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'product' AND column_name = 'sale_stop_source'", Long.class, SCHEMA))
                .as("V33까지는 product.sale_stop_source 컬럼 없음").isZero();

        migrateTo("34");

        assertThat(jdbc.queryForObject("SELECT sale_stop_source FROM product WHERE id = 1", String.class)).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT sale_stop_source FROM product WHERE id = 5", String.class))
                .as("soft-delete된 STOPPED 행도 백필(불변식 전건 적용)").isEqualTo("ADMIN");
        for (int id = 2; id <= 4; id++) {
            assertThat(jdbc.queryForObject("SELECT sale_stop_source FROM product WHERE id = ?", String.class, id))
                    .as("비-STOPPED 행은 NULL").isNull();
        }
        // 불변식: STOPPED ↔ NOT NULL 위반 0
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE (status = 'STOPPED') <> (sale_stop_source IS NOT NULL)",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT column_type FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'product' AND column_name = 'sale_stop_source'", String.class, SCHEMA))
                .isEqualTo("enum('ADMIN','SELLER')");
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'product' AND column_name = 'sale_stop_source'", String.class, SCHEMA)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("34");
    }

    private void seedProduct(long id, String tag, String status, String deletedAt) {
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at, "
                + "deleted_at) VALUES (?, ?, 1, 1, ?, ?, 1000, NOW(6), NOW(6), ?)", id, pid("prd_", tag), tag, status, deletedAt);
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

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

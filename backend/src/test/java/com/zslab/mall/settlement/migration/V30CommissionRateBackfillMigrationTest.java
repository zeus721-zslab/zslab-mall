package com.zslab.mall.settlement.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.support.MariaDbTestContainer;
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
 * V30 수수료율 스냅샷 backfill 검증(Track 85·V22ProductNameBackfillMigrationTest 패턴). 별도 스키마를 V29까지 마이그레이션 → 셀러(기본율
 * 1000·개별율 500)·상품·주문 품목을 시딩 → V30 적용 → order_item.commission_rate 전 행 backfill(셀러 현행율)·NOT NULL 전환·seller 1000→NULL
 * 환원·500 유지·category.commission_rate 신설을 확인한다. 통합 테스트 컨텍스트는 빈 스키마에 전체 마이그레이션을 한 번에 적용하므로
 * backfill 경로가 실행되지 않는다(본 테스트가 유일한 커버).
 */
class V30CommissionRateBackfillMigrationTest {

    private static final String SCHEMA = "track85_v30_backfill";
    private static final String ROOT_USER = "root";

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
    @DisplayName("V29 상태의 기존 order_item → V30 적용 후 commission_rate = 셀러 현행율 backfill·NOT NULL / seller 1000→NULL·500 유지 / category 컬럼 신설")
    void v30_backfillsCommissionRateFromSellerAndRelaxesSeller() {
        migrateTo("29");
        // FK 부모 그래프 없이 시딩(세션 단일 커넥션이라 FOREIGN_KEY_CHECKS=0 유지). 모든 값은 ? 바인딩.
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                + "VALUES (1, ?, '기본율셀러', '대표', 'ACTIVE', 1000, NOW(6), NOW(6))", pid("slr_", "V30DEFAULT"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, commission_rate, created_at, updated_at) "
                + "VALUES (2, ?, '개별율셀러', '대표', 'ACTIVE', 500, NOW(6), NOW(6))", pid("slr_", "V30CUSTOM"));
        jdbc.update("INSERT INTO category (id, parent_id, display_name, depth, sort_order, created_at, updated_at) "
                + "VALUES (1, NULL, '데모', 0, 0, NOW(6), NOW(6))");
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (1, ?, 1, 1, '상품', 'SALE', 1000, NOW(6), NOW(6))", pid("prd_", "V30PRODUCT"));
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "created_at, updated_at) VALUES (1, ?, 1, 'V30-ORDER', 'PAID', 2000, 0, 0, NOW(6), NOW(6))", pid("ord_", "V30ORDER"));
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at, product_name) VALUES (1, ?, 1, 1, 1, 1, 1, 1000, 1000, 'PAID', "
                + "NOW(6), NOW(6), '상품')", pid("oit_", "V30ITEM1"));
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at, product_name) VALUES (2, ?, 1, 1, 1, 2, 1, 1000, 1000, 'PAID', "
                + "NOW(6), NOW(6), '상품')", pid("oit_", "V30ITEM2"));
        // 상품 미존재(soft-delete 아님·행 자체 없음) 품목도 셀러율로 backfill된다
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at, product_name) VALUES (3, ?, 1, 999, 999, 1, 1, 1000, 1000, 'PAID', "
                + "NOW(6), NOW(6), '상품')", pid("oit_", "V30ITEM3"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'order_item' AND column_name = 'commission_rate'", Long.class, SCHEMA))
                .as("V29까지는 order_item.commission_rate 컬럼 없음").isZero();

        migrateTo("30");

        assertThat(jdbc.queryForObject("SELECT commission_rate FROM order_item WHERE id = 1", Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM order_item WHERE id = 2", Integer.class))
                .as("개별율 셀러 품목은 셀러 현행율").isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM order_item WHERE id = 3", Integer.class))
                .as("상품 미존재 품목도 셀러율(LEFT JOIN)").isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'order_item' AND column_name = 'commission_rate'", String.class, SCHEMA)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM seller WHERE id = 1", Integer.class))
                .as("V14 기본값 1000 셀러는 개별 계약 없음(NULL)으로 환원").isNull();
        assertThat(jdbc.queryForObject("SELECT commission_rate FROM seller WHERE id = 2", Integer.class)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'seller' AND column_name = 'commission_rate'", String.class, SCHEMA)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'seller' AND column_name = 'commission_rate'", String.class, SCHEMA))
                .as("seller DEFAULT 제거").isEqualTo("NULL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'category' AND column_name = 'commission_rate'", Long.class, SCHEMA)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("30");
    }

    private void migrateTo(String targetVersion) {
        Flyway.configure()
                .dataSource(schemaUrl, ROOT_USER, MariaDbTestContainer.INSTANCE.getPassword())
                .locations("classpath:db/migration")
                .target(targetVersion)
                .load()
                .migrate();
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

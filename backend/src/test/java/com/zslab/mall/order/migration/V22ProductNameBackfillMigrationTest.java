package com.zslab.mall.order.migration;

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
 * V22 order_item.product_name backfill 검증(Track 76). Spring 컨텍스트 없이 Flyway를 프로그램적으로 구동한다:
 * 별도 스키마를 V21까지 마이그레이션 → 기존 주문 행(soft-delete 상품 포함)을 시딩 → V22 적용 → 전 행 backfill·NOT NULL 전환 확인.
 * 통합 테스트 컨텍스트는 빈 스키마에 전체 마이그레이션을 한 번에 적용하므로 backfill 경로가 실행되지 않는다(본 테스트가 유일한 커버).
 *
 * <p>root 접속(Testcontainers MariaDB는 MARIADB_ROOT_PASSWORD=컨테이너 비밀번호)으로 전용 DB를 만들고 종료 시 DROP한다.
 */
class V22ProductNameBackfillMigrationTest {

    private static final String SCHEMA = "track76_v22_backfill";
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
    @DisplayName("V21 상태의 기존 order_item(활성 상품·soft-delete 상품 참조) → V22 적용 후 product_name 전 행 backfill·NOT NULL")
    void v22_backfillsProductNameFromProductIncludingSoftDeleted() {
        migrateTo("21");
        // FK 부모 그래프 없이 시딩(세션 단일 커넥션이라 FOREIGN_KEY_CHECKS=0 유지). 모든 값은 ? 바인딩.
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                + "VALUES (1, ?, 1, 1, '활성상품', 'SALE', 1000, NOW(6), NOW(6))", pid("prd_", "V22ACTIVE"));
        jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at, deleted_at) "
                + "VALUES (2, ?, 1, 1, '삭제된상품', 'SALE', 1000, NOW(6), NOW(6), NOW(6))", pid("prd_", "V22DELETED"));
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                + "created_at, updated_at) VALUES (1, ?, 1, 'V22-ORDER', 'PAID', 2000, 0, 0, NOW(6), NOW(6))", pid("ord_", "V22ORDER"));
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at) VALUES (1, ?, 1, 1, 1, 1, 1, 1000, 1000, 'PAID', NOW(6), NOW(6))",
                pid("oit_", "V22ITEM1"));
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                + "total_price, item_status, created_at, updated_at) VALUES (2, ?, 1, 2, 2, 1, 1, 1000, 1000, 'PAID', NOW(6), NOW(6))",
                pid("oit_", "V22ITEM2"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'order_item' AND column_name = 'product_name'", Long.class, SCHEMA))
                .as("V21까지는 product_name 컬럼 없음").isZero();

        migrateTo("22");

        assertThat(jdbc.queryForObject("SELECT product_name FROM order_item WHERE id = 1", String.class)).isEqualTo("활성상품");
        assertThat(jdbc.queryForObject("SELECT product_name FROM order_item WHERE id = 2", String.class))
                .as("soft-delete 상품도 native 조인으로 backfill").isEqualTo("삭제된상품");
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'order_item' AND column_name = 'product_name'", String.class, SCHEMA)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("22");
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

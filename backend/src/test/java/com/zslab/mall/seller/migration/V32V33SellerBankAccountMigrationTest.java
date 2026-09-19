package com.zslab.mall.seller.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import com.zslab.mall.support.MariaDbTestContainer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V32(주 계좌 UNIQUE·FK RESTRICT) + V33(계좌번호 암호화 백필) 검증(Track 89-F·D-188·V30 백필 테스트 패턴). 별도 스키마를 V31까지 올려
 * 평문 계좌를 시딩한 뒤 V32·V33을 적용한다. V33은 Spring 빈으로만 Flyway에 등록되므로 standalone Flyway에는 {@code javaMigrations(...)}로
 * 테스트 키 인스턴스를 직접 넣는다. 통합 테스트 컨텍스트는 빈 스키마에 전체 마이그레이션을 한 번에 적용해 백필 대상이 0건이라 본 테스트가
 * 백필 경로의 유일한 커버다. 계좌 실값이 아닌 테스트 상수만 쓴다.
 */
class V32V33SellerBankAccountMigrationTest {

    private static final String SCHEMA = "track89f_v32_v33";
    private static final String ROOT_USER = "root";
    private static final byte[] TEST_KEY = "track89f-test-key-0123456789abcd".getBytes(StandardCharsets.UTF_8);
    private static final String PLAIN_A = "110-000-000011";
    private static final String PLAIN_B = "220-000-000022";
    private static final String PLAIN_C = "330-000-000033";

    private String schemaUrl;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private AesGcmTextEncryptor encryptor;

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
        encryptor = new AesGcmTextEncryptor(TEST_KEY);
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
    @DisplayName("V31 평문 3행 → V32: FK ON UPDATE RESTRICT·generated 컬럼·UNIQUE / V33: 전 행 v1: 암호문·복호 시 원문 일치·이력 33")
    void v32_constraints_and_v33_backfill() {
        migrateTo("31");
        seedSellers();
        insertPlain(1, 1, PLAIN_A, 1);
        insertPlain(2, 2, PLAIN_B, 1);
        insertPlain(3, 2, PLAIN_C, 0);

        migrateTo("33");

        // V32 ① FK 규칙
        Map<String, Object> fk = jdbc.queryForMap("SELECT UPDATE_RULE, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = ? AND CONSTRAINT_NAME = 'fk_seller_bank_account_seller'", SCHEMA);
        assertThat(fk.get("UPDATE_RULE")).isEqualTo("RESTRICT");
        assertThat(fk.get("DELETE_RULE")).as("ON DELETE는 원래 RESTRICT·무변경").isEqualTo("RESTRICT");
        // V32 ② generated 컬럼: 주 계좌 행만 seller_id·비주계좌 NULL
        assertThat(jdbc.queryForObject("SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? "
                + "AND TABLE_NAME = 'seller_bank_account' AND COLUMN_NAME = 'primary_seller_id'", String.class, SCHEMA))
                .containsIgnoringCase("STORED GENERATED");
        assertThat(jdbc.queryForList("SELECT primary_seller_id FROM seller_bank_account ORDER BY id", Long.class))
                .containsExactly(1L, 2L, null);
        // V32 ③ UNIQUE 존재
        assertThat(jdbc.queryForObject("SELECT NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? "
                + "AND TABLE_NAME = 'seller_bank_account' AND INDEX_NAME = 'uk_seller_bank_account_primary'", Long.class, SCHEMA))
                .isZero();

        // V33 백필: 전 행 v1: 접두사·평문 미잔존·복호 시 원문
        List<String> stored = jdbc.queryForList("SELECT account_number FROM seller_bank_account ORDER BY id", String.class);
        assertThat(stored).allMatch(AesGcmTextEncryptor::isEncrypted);
        assertThat(stored).noneMatch(value -> value.contains(PLAIN_A) || value.contains(PLAIN_B) || value.contains(PLAIN_C));
        assertThat(stored.stream().map(encryptor::decrypt).toList()).containsExactly(PLAIN_A, PLAIN_B, PLAIN_C);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account WHERE account_number NOT LIKE 'v1:%'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("33");
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '33'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("V33 멱등: 이미 암호문인 행은 건너뛰고(암호문 불변) 새 평문 행만 암호화한다 · 대상 0건이면 no-op")
    void v33_isIdempotent() throws Exception {
        migrateTo("31");
        seedSellers();
        insertPlain(1, 1, PLAIN_A, 1);
        migrateTo("33");
        String firstCipher = jdbc.queryForObject("SELECT account_number FROM seller_bank_account WHERE id = 1", String.class);

        // 재실행 1: 대상 0건 → 암호문 그대로
        runV33Directly();
        assertThat(jdbc.queryForObject("SELECT account_number FROM seller_bank_account WHERE id = 1", String.class))
                .isEqualTo(firstCipher);

        // 재실행 2: raw SQL로 평문이 새로 들어온 상황 → 그 행만 암호화·기존 암호문 불변
        insertPlain(2, 2, PLAIN_B, 1);
        runV33Directly();
        assertThat(jdbc.queryForObject("SELECT account_number FROM seller_bank_account WHERE id = 1", String.class))
                .isEqualTo(firstCipher);
        String secondCipher = jdbc.queryForObject("SELECT account_number FROM seller_bank_account WHERE id = 2", String.class);
        assertThat(AesGcmTextEncryptor.isEncrypted(secondCipher)).isTrue();
        assertThat(encryptor.decrypt(secondCipher)).isEqualTo(PLAIN_B);
    }

    @Test
    @DisplayName("V32 ③ UNIQUE: 셀러당 주 계좌 2건이면 마이그레이션 실패(평문·FK 상태 유지) → 중복 정리·repair 후 재실행 성공(IF NOT EXISTS 멱등) → 이후 2번째 주 계좌 INSERT 거부")
    void v32_uniqueViolation_failsThenRecovers() {
        migrateTo("31");
        seedSellers();
        insertPlain(1, 1, PLAIN_A, 1);
        insertPlain(2, 1, PLAIN_B, 1); // 같은 셀러 주 계좌 2건(SLR-3 위반 데이터)

        assertThatThrownBy(() -> migrateTo("32")).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '32'", Integer.class))
                .as("실패 행 기록").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account WHERE account_number NOT LIKE 'v1:%'", Long.class))
                .as("V33 미도달·평문 유지").isEqualTo(2);

        // 운영 복구 절차(ops-checklist P5): 중복 정리 → flyway repair → 재기동(재실행). ①②는 이미 적용됐어도 IF [NOT] EXISTS로 통과한다.
        jdbc.update("UPDATE seller_bank_account SET is_primary = 0 WHERE id = 2");
        flyway("33").repair();
        migrateTo("33");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("33");
        assertThat(jdbc.queryForList("SELECT primary_seller_id FROM seller_bank_account ORDER BY id", Long.class))
                .containsExactly(1L, null);

        // 제약 동작: 같은 셀러에 두 번째 주 계좌 INSERT → UNIQUE 위반 / 비주계좌는 무제한
        assertThatThrownBy(() -> insertPlain(3, 1, PLAIN_C, 1)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_seller_bank_account_primary");
        insertPlain(4, 1, PLAIN_C, 0);
        insertPlain(5, 1, PLAIN_C, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account WHERE seller_id = 1", Long.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("FK 실동작(외부 검토 지적 1): V31 CASCADE에서는 seller.id 갱신이 계좌로 전파 → V32 후 같은 갱신 거부(ON UPDATE RESTRICT) · 계좌 있는 셀러 삭제 거부(ON DELETE RESTRICT·무변경) · 미존재 seller_id 계좌 INSERT 거부")
    void v32_foreignKeyRestrictBehavior() {
        migrateTo("31");
        seedSellers();
        insertPlain(1, 1, PLAIN_A, 1);

        // V1 원형 ON UPDATE CASCADE: 부모 id 갱신이 자식 seller_id로 전파된다(운영에서는 seller.id가 AUTO_INCREMENT라 갱신 경로가 없음 —
        // 여기서는 규칙 전환을 실증하기 위해서만 갱신한다).
        jdbc.update("UPDATE seller SET id = 10 WHERE id = 1");
        assertThat(jdbc.queryForObject("SELECT seller_id FROM seller_bank_account WHERE id = 1", Long.class)).isEqualTo(10L);
        jdbc.update("UPDATE seller SET id = 1 WHERE id = 10");
        assertThat(jdbc.queryForObject("SELECT seller_id FROM seller_bank_account WHERE id = 1", Long.class)).isEqualTo(1L);

        migrateTo("33");

        // ON UPDATE RESTRICT: 같은 갱신이 이제 거부되고 자식은 그대로
        assertThatThrownBy(() -> jdbc.update("UPDATE seller SET id = 10 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT seller_id FROM seller_bank_account WHERE id = 1", Long.class)).isEqualTo(1L);
        // ON DELETE RESTRICT(원래부터·무변경): 계좌가 있는 셀러 삭제 거부
        assertThatThrownBy(() -> jdbc.update("DELETE FROM seller WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller WHERE id = 1", Long.class)).isEqualTo(1L);
        // 미존재 seller_id 계좌 INSERT 거부(FK 재생성 후에도 참조 무결성 유지)
        assertThatThrownBy(() -> insertPlain(9, 99, PLAIN_B, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_bank_account", Long.class)).isEqualTo(1L);
    }

    // ---------- helpers ----------

    private Flyway flyway(String targetVersion) {
        return Flyway.configure()
                .dataSource(schemaUrl, ROOT_USER, MariaDbTestContainer.INSTANCE.getPassword())
                .locations("classpath:db/migration")
                .javaMigrations(new V33__Encrypt_seller_bank_account_numbers(encryptor))
                .target(targetVersion)
                .load();
    }

    private void migrateTo(String targetVersion) {
        flyway(targetVersion).migrate();
    }

    /** Flyway 이력과 무관하게 V33 본문을 같은 커넥션에서 직접 실행한다(멱등성 검증용). */
    private void runV33Directly() throws Exception {
        V33__Encrypt_seller_bank_account_numbers migration = new V33__Encrypt_seller_bank_account_numbers(encryptor);
        Connection connection = dataSource.getConnection();
        migration.migrate(new Context() {
            @Override
            public Configuration getConfiguration() {
                return flyway("33").getConfiguration();
            }

            @Override
            public Connection getConnection() {
                return connection;
            }
        });
    }

    private void seedSellers() {
        // seller는 FK 부모가 없어 FK 검사를 끄지 않고 시딩한다(외부 검토 지적 1: 세션 커넥션이 단일이라 =0을 남기면 이후 FK 단언이 무력화됨).
        // 계좌 INSERT는 이 두 셀러를 실제로 참조한다. 모든 값은 ? 바인딩.
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (1, ?, '셀러A', '대표', 'ACTIVE', NOW(6), NOW(6))", pid("slr_", "V33SELLERA"));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (2, ?, '셀러B', '대표', 'ACTIVE', NOW(6), NOW(6))", pid("slr_", "V33SELLERB"));
    }

    private void insertPlain(long id, long sellerId, String accountNumber, int isPrimary) {
        jdbc.update("INSERT INTO seller_bank_account (id, seller_id, bank_code, account_number, account_holder, is_primary, "
                + "verified_at, status, created_at, updated_at) VALUES (?, ?, 'KB', ?, '예금주', ?, NOW(6), 'VERIFIED', NOW(6), NOW(6))",
                id, sellerId, accountNumber, isPrimary);
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}

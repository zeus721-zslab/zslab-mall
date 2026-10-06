package com.zslab.mall.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * DB 세션 시간대 KST 고정(D-264) 검증. 앱 DataSource에서 꺼낸 커넥션의 세션 시간대와 DB {@code NOW()}가
 * KST(Asia/Seoul) 현재 벽시계와 같은지 확인한다.
 *
 * <p>풀 최대 크기만큼 커넥션을 동시에 붙잡아 검사한다 — 이미 풀에 있던 커넥션(기동 중 Flyway가 쓴 것 포함)과
 * 새로 만들어진 커넥션을 모두 거치게 해, 한 커넥션만 우연히 맞는 경우를 통과로 보지 않는다.
 */
class DbSessionTimeZoneIntegrationTest extends AbstractIntegrationTest {

    private static final String KST_OFFSET = "+09:00";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // NOW() 조회와 KST 현재 시각 측정 사이의 실행 지연 허용치. UTC 세션이면 9시간 차이라 이 값으로 충분히 구분된다.
    private static final Duration MAX_CLOCK_GAP = Duration.ofSeconds(5);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Value("${spring.datasource.hikari.maximum-pool-size}")
    private int maximumPoolSize;

    @Test
    @DisplayName("풀의 모든 커넥션: @@session.time_zone = '+09:00' · DB NOW()와 KST 현재 시각 차이 5초 이내")
    void everyPooledConnection_sessionTimeZoneIsKst() throws SQLException {
        List<Connection> held = new ArrayList<>();
        try {
            for (int index = 0; index < maximumPoolSize; index++) {
                held.add(dataSource.getConnection());
            }
            for (Connection connection : held) {
                assertSessionIsKst(connection);
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("Flyway는 앱 풀(Hikari)이 아닌 전용 커넥션으로 마이그레이션한다 — 마이그레이션의 세션 SET이 풀에 남지 않게")
    void flyway_usesDedicatedDataSource_notAppPool() {
        // 위 테스트는 이 컨텍스트가 실제 마이그레이션을 수행한 첫 컨텍스트일 때만 Flyway 분리 회귀를 잡으므로 실행 순서와 무관하게 따로 고정한다.
        DataSource flywayDataSource = flyway.getConfiguration().getDataSource();

        assertThat(flywayDataSource).isNotSameAs(dataSource).isNotInstanceOf(HikariDataSource.class);
    }

    private void assertSessionIsKst(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT @@session.time_zone, NOW(6)")) {
            assertThat(resultSet.next()).isTrue();
            String sessionTimeZone = resultSet.getString(1);
            // NOW()는 DATETIME이라 드라이버 시간대 변환 없이 벽시계 문자열 그대로 읽는다.
            LocalDateTime databaseNow = LocalDateTime.parse(resultSet.getString(2).replace(' ', 'T'));
            Duration gap = Duration.between(databaseNow, LocalDateTime.now(KST)).abs();

            assertThat(sessionTimeZone).isEqualTo(KST_OFFSET);
            assertThat(gap).isLessThanOrEqualTo(MAX_CLOCK_GAP);
        }
    }
}

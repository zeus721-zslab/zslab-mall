package com.zslab.mall.seller.migration;

import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * V33: seller_bank_account.account_number 평문 → AES-256-GCM 암호문 백필(Track 89-F·D-188·SLR-2).
 *
 * <p><b>왜 Java 마이그레이션인가</b>: 암호화 키는 앱 env({@code BANK_ACCOUNT_ENCRYPTION_KEY})에만 있어 Flyway SQL은 키를 모른다.
 * DB 함수(AES_ENCRYPT)로 백필하면 앱 Converter의 형식(GCM·IV·{@code v1:})과 달라 복호화가 불가능하다. Spring 빈으로 등록하면
 * Spring Boot {@code FlywayAutoConfiguration}이 {@code JavaMigration} 빈을 Flyway에 주입하므로(3.4.1 실측) 키가 있는 암복호기를
 * 생성자로 받을 수 있고, 스키마 이력(flyway_schema_history 33)과 데이터 상태가 1:1로 묶이며, Flyway는 컨텍스트 초기화 단계라
 * 앱이 요청을 받기 전에 완료된다.
 *
 * <p><b>배치 위치 트랩</b>: 이 클래스를 {@code db.migration}(Flyway locations) 패키지에 두면 Flyway classpath 스캔이 no-arg 생성자로
 * 인스턴스화를 시도해 실패한다. 반드시 locations 밖({@code com.zslab.mall.seller.migration})에 두고 빈으로만 등록한다. 클래스명은
 * Flyway 규약({@code V<버전>__<설명>})을 따라야 버전이 추출된다.
 *
 * <p><b>멱등·원자성</b>: {@code v1:} 접두사가 없는 행만 암호화한다(재실행 시 이미 암호화된 행은 건너뜀). DML만 수행하므로 Flyway
 * 트랜잭션 안에서 원자적이며, 예외 시 전부 롤백돼 평문이 그대로 남고 flyway_schema_history에 실패 행이 기록된다 →
 * 원인 수정 후 {@code flyway repair}(실패 행 제거) → 재기동으로 다시 시도한다(ops-checklist P6).
 *
 * <p><b>로그</b>: 처리 건수·id만 남긴다. 계좌번호 평문·암호문은 어떤 경우에도 로그에 싣지 않는다.
 */
@Component
public class V33__Encrypt_seller_bank_account_numbers extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V33__Encrypt_seller_bank_account_numbers.class);

    // 모든 변수는 ? 바인딩 사용, SQL injection 위험 없음(접두사 리터럴은 상수)
    private static final String SELECT_PLAINTEXT_ROWS =
            "SELECT id, account_number FROM seller_bank_account WHERE account_number NOT LIKE ? ORDER BY id";
    private static final String UPDATE_ROW = "UPDATE seller_bank_account SET account_number = ? WHERE id = ?";

    private final AesGcmTextEncryptor encryptor;

    public V33__Encrypt_seller_bank_account_numbers(AesGcmTextEncryptor bankAccountEncryptor) {
        this.encryptor = bankAccountEncryptor;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        Map<Long, String> plaintextById = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_PLAINTEXT_ROWS)) {
            select.setString(1, AesGcmTextEncryptor.VERSION_PREFIX + "%");
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    plaintextById.put(rows.getLong("id"), rows.getString("account_number"));
                }
            }
        }
        if (plaintextById.isEmpty()) {
            log.info("[V33] 암호화 대상 평문 계좌 0건 — 건너뜀(멱등)");
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(UPDATE_ROW)) {
            for (Map.Entry<Long, String> row : plaintextById.entrySet()) {
                update.setString(1, encryptor.encrypt(row.getValue()));
                update.setLong(2, row.getKey());
                int updated = update.executeUpdate();
                if (updated != 1) {
                    throw new IllegalStateException("[V33] 계좌 id=" + row.getKey() + " UPDATE 영향 행 " + updated + "(1 기대)");
                }
            }
        }
        log.info("[V33] 계좌번호 암호화 백필 완료: {}건 ids={}", plaintextById.size(), plaintextById.keySet());
    }
}

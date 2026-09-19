package com.zslab.mall.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.ZslabMallApplication;
import com.zslab.mall.support.MariaDbTestContainer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * prod 프로파일 계좌 키 fail-fast(Track 89-F·D-188 결정 3). application-prod.yml은 {@code bank-account.encryption-key=${BANK_ACCOUNT_ENCRYPTION_KEY}}
 * 기본값이 없으므로 (1) env 미주입이면 placeholder 해석 실패, (2) 빈 값·형식 오류면 {@link BankAccountEncryptionConfig}의 원인별 메시지로
 * 컨텍스트가 뜨지 않아야 한다. 잘못된 키로 기동하면 기존 암호문을 읽을 수 없고 새 데이터가 다른 키로 써지므로 기동 차단이 데이터 보호다.
 *
 * <p>실 컨텍스트를 {@link SpringApplicationBuilder}로 직접 띄운다(캐시 밖·프로파일 prod·싱글톤 MariaDB·JWT는 테스트 더미). 서블릿 컨테이너는
 * 띄우지 않는다({@code WebApplicationType.NONE}·빈 생성 실패만 확인하면 충분).
 */
class ProdBankAccountKeyFailFastTest {

    private static final String DUMMY_JWT_SECRET = "prod-failfast-test-dummy-secret-please-ignore-32b";

    @Test
    @DisplayName("prod: BANK_ACCOUNT_ENCRYPTION_KEY 미주입 → placeholder 해석 실패로 기동 중단")
    void missingKey_failsToStart() {
        assertThatThrownBy(() -> run(Map.of()))
                .hasStackTraceContaining("BANK_ACCOUNT_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("prod: 키가 빈 문자열(compose가 미설정 .env를 빈 값으로 전달) → 원인 메시지로 기동 중단")
    void blankKey_failsToStart() {
        assertThatThrownBy(() -> run(Map.of("BANK_ACCOUNT_ENCRYPTION_KEY", "")))
                .hasStackTraceContaining("BANK_ACCOUNT_ENCRYPTION_KEY").hasStackTraceContaining("비어 있습니다");
    }

    @Test
    @DisplayName("prod: 키 길이 오류(Base64 16바이트) → 길이 메시지로 기동 중단")
    void wrongLengthKey_failsToStart() {
        assertThatThrownBy(() -> run(Map.of("BANK_ACCOUNT_ENCRYPTION_KEY", "MDEyMzQ1Njc4OWFiY2RlZg=="))) // 16바이트
                .hasStackTraceContaining("32바이트").hasStackTraceContaining("현재 16바이트");
    }

    private static void run(Map<String, String> extraProperties) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ZslabMallApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties(
                        "spring.datasource.url=" + MariaDbTestContainer.INSTANCE.getJdbcUrl(),
                        "spring.datasource.username=" + MariaDbTestContainer.INSTANCE.getUsername(),
                        "spring.datasource.password=" + MariaDbTestContainer.INSTANCE.getPassword(),
                        "spring.datasource.driver-class-name=" + MariaDbTestContainer.INSTANCE.getDriverClassName(),
                        "jwt.secret=" + DUMMY_JWT_SECRET,
                        "catalog.demo-seed.enabled=false");
        extraProperties.forEach((key, value) -> builder.properties(key + "=" + value));
        try (ConfigurableApplicationContext context = builder.run()) {
            assertThat(context.isActive()).as("키 없이 prod 컨텍스트가 떠서는 안 된다").isFalse();
        }
    }
}

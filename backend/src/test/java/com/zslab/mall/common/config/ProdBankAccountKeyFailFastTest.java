package com.zslab.mall.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.ZslabMallApplication;
import com.zslab.mall.support.MariaDbTestContainer;
import java.nio.file.Path;
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
 *
 * <p><b>LOG_PATH 트랩(STEP 485·CI 실패 원인)</b>: prod 프로파일의 logback-spring.xml은 {@code ${LOG_PATH:/app/logs}}에 JSON 파일 appender를 연다.
 * CI 러너(Linux)는 {@code /app}을 만들 수 없어 Logback 설정 오류가 키 검증보다 먼저 컨텍스트를 죽이고(로컬 Windows는 C 드라이브 루트의 app/logs 디렉터리가
 * 생성돼 통과), 실패한 SpringApplication의 cleanUp이 로깅 시스템 초기화 마커를 지워 뒤이은 prod 컨텍스트(ProdSecurityContextSmokeTest)도
 * 같은 오류로 실패한다. 테스트는 LOG_PATH를 임시 디렉터리로 주입해 환경 의존을 없앤다 — 검증 대상(키 fail-fast)은 그대로다.
 * {@code @TempDir}는 쓰지 않는다: 실패한 컨텍스트가 Logback 파일 핸들을 닫지 않아 Windows에서 정리 단계가 "Failed to close extension context"로
 * 실패한다 → java.io.tmpdir 아래 고정 디렉터리(ProdSecurityContextSmokeTest와 같은 방식).
 */
class ProdBankAccountKeyFailFastTest {

    private static final String DUMMY_JWT_SECRET = "prod-failfast-test-dummy-secret-please-ignore-32b";
    private static final Path LOG_DIR = Path.of(System.getProperty("java.io.tmpdir"), "zslab-prod-failfast-logs");

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

    private void run(Map<String, String> extraProperties) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ZslabMallApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties(
                        "spring.datasource.url=" + MariaDbTestContainer.INSTANCE.getJdbcUrl(),
                        "spring.datasource.username=" + MariaDbTestContainer.INSTANCE.getUsername(),
                        "spring.datasource.password=" + MariaDbTestContainer.INSTANCE.getPassword(),
                        "spring.datasource.driver-class-name=" + MariaDbTestContainer.INSTANCE.getDriverClassName(),
                        "jwt.secret=" + DUMMY_JWT_SECRET,
                        // D-230: prod는 데모 보호 계정 0개면 기동 실패 — 계좌 키 fail-fast만 격리해 보려고 더미 1개를 채운다.
                        "DEMO_PROTECTED_EMAILS=prod-fail-fast-demo@zslab.test",
                        "catalog.demo-seed.enabled=false");
        extraProperties.forEach((key, value) -> builder.properties(key + "=" + value));
        // LOG_PATH는 커맨드라인 인자(최고 우선순위)로 넣는다 — builder.properties(기본 속성)는 OS env LOG_PATH에 밀려 로컬 재현이 어긋난다.
        try (ConfigurableApplicationContext context = builder.run("--LOG_PATH=" + LOG_DIR.toAbsolutePath())) {
            assertThat(context.isActive()).as("키 없이 prod 컨텍스트가 떠서는 안 된다").isFalse();
        }
    }
}

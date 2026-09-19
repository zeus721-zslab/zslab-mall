package com.zslab.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zslab.mall.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * prod 프로파일 SecurityFilterChain 스모크 테스트(Track 33 P5·D-116 §8 자격증명 트랙 prod 컨텍스트 로드 동반). 단일
 * 체인이 prod에서도 (1) 컨텍스트 로드 (2) 미인증 보호 endpoint → 401 UNAUTHENTICATED (3) 유효 토큰 → 인증 통과(비-401)를
 * 실제로 강제하는지 최소 검증한다. 과거 prod 체인이 {@code anyRequest().permitAll()}이던 회귀(prod 전면 개방)를 차단한다.
 *
 * <p>prod 프로파일은 application-prod.yml에서 {@code jwt.secret=${JWT_SECRET}}(기본값 없음·env 미주입 시 기동 실패)이므로
 * 테스트 전용 더미 시크릿을 @DynamicPropertySource로 주입한다(실 운영 값 아님). 컨텍스트 로드에 DB가 필요해 다른 통합
 * 테스트와 동일하게 MariaDBContainer + Flyway로 실 스키마를 띄운다.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
// STEP 485(CI 실패 원인): prod logback-spring.xml의 JSON_FILE appender가 ${LOG_PATH:/app/logs}를 연다. CI 러너(Linux)는 /app을 만들 수 없어
// Logback 설정 오류로 컨텍스트가 죽는다(종전엔 앞선 비-prod 컨텍스트가 로깅을 먼저 초기화해 우연히 통과·ProdBankAccountKeyFailFastTest의
// 실패 cleanUp이 초기화 마커를 지운 뒤로는 재초기화). 로깅 초기화(EnvironmentPreparedEvent)보다 먼저 환경에 실리는 인라인 테스트 속성으로
// LOG_PATH를 임시 디렉터리에 고정한다(@DynamicPropertySource는 그 시점에 아직 없음·OS env보다 우선).
@TestPropertySource(properties = "LOG_PATH=${java.io.tmpdir}/zslab-prod-smoke-logs")
class ProdSecurityContextSmokeTest extends AbstractIntegrationTest {

    // 테스트 전용 더미 — 운영 시크릿 아님. HS256 요건상 32바이트 이상.
    private static final String DUMMY_JWT_SECRET = "prod-smoke-test-dummy-secret-please-ignore-min-32-bytes";
    // Track 89-F: prod yml ${BANK_ACCOUNT_ENCRYPTION_KEY}도 기본값이 없어(JWT 동형 fail-fast) 테스트 전용 더미 키(Base64 32바이트)를 주입한다.
    // 미주입 시 컨텍스트 로드 실패는 ProdBankAccountKeyFailFastTest가 별도로 고정한다.
    private static final String DUMMY_BANK_ACCOUNT_KEY = "cHJvZC1zbW9rZS10ZXN0LWR1bW15LWJhbmsta2V5MzI=";
    private static final String PROTECTED_ADMIN_PATH = "/api/v1/admin/__prod_smoke_probe__";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // prod yml ${JWT_SECRET} 미주입 기동 실패를 테스트 전용 더미로 회피(최고 우선순위로 shadow).
        // 싱글톤 datasource 4-property는 상위 AbstractIntegrationTest가 주입(@DynamicPropertySource는 계층에서 합쳐짐).
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
        registry.add("bank-account.encryption-key", () -> DUMMY_BANK_ACCOUNT_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenProvider tokenProvider;

    @Test
    @DisplayName("(1) prod 프로파일 컨텍스트 로드 성공(단일 SecurityFilterChain 기동)")
    void contextLoads() {
        // @SpringBootTest 컨텍스트 로드 자체가 검증 — 주입 성공 시 통과.
        assertThat(tokenProvider).isNotNull();
    }

    @Test
    @DisplayName("(2) 미인증 보호 endpoint(/api/v1/admin/**) → 401 UNAUTHENTICATED(prod 전면개방 회귀 차단)")
    void protectedEndpoint_noAuth_returns401() throws Exception {
        mockMvc.perform(get(PROTECTED_ADMIN_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("(3) 유효 Bearer 토큰(ADMIN) → 인증 통과(비-401)")
    void protectedEndpoint_validToken_passesAuthentication() throws Exception {
        String token = tokenProvider.issue(1L, ActorRole.ADMIN);
        mockMvc.perform(get(PROTECTED_ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                // 인증·인가(ROLE_ADMIN) 통과 후 핸들러 부재로 404 — 핵심은 401이 아님(인증 성공).
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }
}

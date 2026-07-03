package com.zslab.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * prod 프로파일 SecurityFilterChain 스모크 테스트(Track 33 P5·D-116 §8 자격증명 트랙 prod 컨텍스트 로드 동반). 단일
 * 체인이 prod에서도 (1) 컨텍스트 로드 (2) 미인증 보호 endpoint → 401 UNAUTHENTICATED (3) 유효 토큰 → 인증 통과(비-401)를
 * 실제로 강제하는지 최소 검증한다. 과거 prod 체인이 {@code anyRequest().permitAll()}이던 회귀(prod 전면 개방)를 차단한다.
 *
 * <p>prod 프로파일은 application-prod.yml에서 {@code jwt.secret=${JWT_SECRET}}(기본값 없음·env 미주입 시 기동 실패)이므로
 * 테스트 전용 더미 시크릿을 @DynamicPropertySource로 주입한다(실 운영 값 아님). 컨텍스트 로드에 DB가 필요해 다른 통합
 * 테스트와 동일하게 MariaDBContainer + Flyway로 실 스키마를 띄운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProdSecurityContextSmokeTest {

    // 테스트 전용 더미 — 운영 시크릿 아님. HS256 요건상 32바이트 이상.
    private static final String DUMMY_JWT_SECRET = "prod-smoke-test-dummy-secret-please-ignore-min-32-bytes";
    private static final String PROTECTED_ADMIN_PATH = "/api/v1/admin/__prod_smoke_probe__";

    static final MariaDBContainer<?> MARIADB;

    static {
        MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
        MARIADB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
        // prod yml ${JWT_SECRET} 미주입 기동 실패를 테스트 전용 더미로 회피(최고 우선순위로 shadow).
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
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

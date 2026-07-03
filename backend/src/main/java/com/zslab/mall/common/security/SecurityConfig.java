package com.zslab.mall.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 골격(Track 31 Phase 1). Stub 인증 파이프라인(필터 → provider → SecurityContext)을 얹되,
 * 인가(hasRole 강제)는 아직 켜지 않는다.
 *
 * <p><b>무회귀 보호책(옵션 b)</b>: 기존 550 테스트는 {@code X-*-Id} 헤더만 보내고 {@code Authorization: Stub}은
 * 보내지 않는다. 이 상태에서 hasRole 규칙이 걸리면 401/403으로 전량 회귀하므로, Phase 1은 인가를
 * {@code anyRequest().permitAll()}로 두어 "인증 채우기 파이프라인 동작"만 검증한다. 경로별 hasRole 매핑은 아래
 * 주석에 준비형으로 보존해 Phase 3(테스트 헬퍼 동반)에서 즉시 활성화한다.
 *
 * <p><b>프로파일 분리</b>: {@code @Profile("!prod")} 체인은 Stub 필터를 얹고, {@code @Profile("prod")} 체인은
 * Stub 없이 permitAll만 둔다. starter-security가 classpath에 오르면 기본 체인이 전 엔드포인트를 잠그는데(라이브 전용
 * 트랩), prod 전용 permitAll 체인이 이를 차단하고 현행(보안 미도입) 동작을 보존한다.
 */
@Configuration
public class SecurityConfig {

    /**
     * 비-prod(local·test 등) 체인 — Stub 인증 파이프라인 + permitAll 골격.
     *
     * @throws Exception HttpSecurity 빌드 예외
     */
    @Bean
    @Profile("!prod")
    SecurityFilterChain stubSecurityFilterChain(
            HttpSecurity http, StubAuthenticationProvider stubAuthenticationProvider) throws Exception {
        AuthenticationManager authenticationManager = new ProviderManager(stubAuthenticationProvider);
        HttpStatusEntryPoint entryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        StubAuthenticationFilter stubFilter =
                new StubAuthenticationFilter(authenticationManager, entryPoint);

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        // === Phase 3 활성 예정: 아래 permitAll을 걷어내고 경로별 규칙(matcher 순서=구체 먼저)으로 교체 ===
                        // .requestMatchers("/api/webhooks/**").permitAll()
                        // .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        // .requestMatchers("/error").permitAll()
                        // 구체 규칙 먼저(claims 세분 — SELLER 3건을 BUYER 광범위 규칙보다 앞에):
                        // .requestMatchers(HttpMethod.POST, "/api/v1/claims/*/approve").hasRole("SELLER")
                        // .requestMatchers(HttpMethod.POST, "/api/v1/claims/*/reject").hasRole("SELLER")
                        // .requestMatchers(HttpMethod.POST, "/api/v1/claims/*/register-exchange-shipment").hasRole("SELLER")
                        // 광범위 규칙:
                        // .requestMatchers("/api/v1/orders/**").hasRole("BUYER")
                        // .requestMatchers("/api/v1/claims/**").hasRole("BUYER")
                        // .requestMatchers("/api/v1/order-items/**").hasRole("SELLER")
                        // .requestMatchers("/api/v1/seller/**").hasRole("SELLER")
                        // .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest()
                        .permitAll())
                .addFilterBefore(stubFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * prod 체인 — Stub 없이 permitAll(현행 보안 미도입 동작 보존·기본 체인 전면 잠금 차단). 실제 인가는 Phase 3에서 도입.
     *
     * @throws Exception HttpSecurity 빌드 예외
     */
    @Bean
    @Profile("prod")
    SecurityFilterChain prodSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}

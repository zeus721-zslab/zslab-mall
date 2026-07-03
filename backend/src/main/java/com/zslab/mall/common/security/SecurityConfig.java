package com.zslab.mall.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 인가 설정(Track 31 Phase 3). Stub 인증 파이프라인(필터 → provider → SecurityContext)이 채운 액터
 * 권한(ROLE_BUYER/SELLER/ADMIN)으로 경로별 hasRole 인가를 강제한다.
 *
 * <p><b>인가 규칙(matcher 순서 = 구체 먼저·first-match)</b>: claims의 SELLER 3건(approve/reject/register-exchange-shipment)을
 * BUYER 광범위 규칙보다 앞에 둔다. permitAll(webhooks·actuator·error) 외 나머지는 {@code authenticated()}로 fail-closed다.
 *
 * <p><b>인증/인가 오류 본문</b>: 필터 계층 401/403은 {@link StubSecurityErrorHandler}가 GlobalExceptionHandler와 동일한
 * ProblemDetail(code·traceId) 포맷으로 응답해 {@code $.code} 계약을 유지한다(미인증→401 UNAUTHENTICATED·권한부족→403 FORBIDDEN).
 *
 * <p><b>프로파일 분리</b>: {@code @Profile("!prod")} 체인은 Stub 인증 + hasRole을 얹고, {@code @Profile("prod")} 체인은
 * Stub 없이 permitAll만 둔다(starter-security 기본 전면 잠금 차단·현행 보안 미도입 동작 보존·실 인증은 후속 트랙).
 */
@Configuration
public class SecurityConfig {

    /**
     * 비-prod(local·test 등) 체인 — Stub 인증 파이프라인 + 경로별 hasRole 강제 인가.
     *
     * @throws Exception HttpSecurity 빌드 예외
     */
    @Bean
    @Profile("!prod")
    SecurityFilterChain stubSecurityFilterChain(
            HttpSecurity http,
            StubAuthenticationProvider stubAuthenticationProvider,
            StubSecurityErrorHandler securityErrorHandler)
            throws Exception {
        AuthenticationManager authenticationManager = new ProviderManager(stubAuthenticationProvider);
        StubAuthenticationFilter stubFilter =
                new StubAuthenticationFilter(authenticationManager, securityErrorHandler);

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/webhooks/**")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        // 구체 규칙 먼저(claims 세분 — SELLER 3건을 BUYER 광범위 규칙보다 앞·first-match):
                        .requestMatchers(HttpMethod.POST, "/api/v1/claims/*/approve")
                        .hasRole("SELLER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/claims/*/reject")
                        .hasRole("SELLER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/claims/*/register-exchange-shipment")
                        .hasRole("SELLER")
                        // 광범위 규칙:
                        .requestMatchers("/api/v1/orders/**")
                        .hasRole("BUYER")
                        .requestMatchers("/api/v1/claims/**")
                        .hasRole("BUYER")
                        .requestMatchers("/api/v1/order-items/**")
                        .hasRole("SELLER")
                        .requestMatchers("/api/v1/seller/**")
                        .hasRole("SELLER")
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(stubFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * prod 체인 — Stub 없이 permitAll(현행 보안 미도입 동작 보존·기본 체인 전면 잠금 차단). 실제 인가는 후속 트랙에서 도입.
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

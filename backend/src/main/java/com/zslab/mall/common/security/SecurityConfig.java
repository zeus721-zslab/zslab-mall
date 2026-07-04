package com.zslab.mall.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Spring Security 인가 설정(Track 31 Phase 3·Track 33 P5 Stub→JWT 단일 체인). JWT 인증 파이프라인(필터 → 검증 →
 * SecurityContext)이 채운 액터 권한(ROLE_BUYER/SELLER/ADMIN)으로 경로별 hasRole 인가를 강제한다.
 *
 * <p><b>인가 규칙(matcher 순서 = 구체 먼저·first-match)</b>: claims의 SELLER 3건(approve/reject/register-exchange-shipment)을
 * BUYER 광범위 규칙보다 앞에 둔다. permitAll(webhooks·actuator·error·auth) 외 나머지는 {@code authenticated()}로 fail-closed다.
 *
 * <p><b>인증/인가 오류 본문</b>: 필터 계층 401/403은 {@link SecurityErrorHandler}가 GlobalExceptionHandler와 동일한
 * ProblemDetail(code·traceId) 포맷으로 응답해 {@code $.code} 계약을 유지한다(미인증→401 UNAUTHENTICATED·권한부족→403 FORBIDDEN).
 *
 * <p><b>필터 위치</b>: {@link JwtAuthenticationFilter}는 검증 실패 시 예외를 전파하고 직접 응답하지 않는다. 따라서
 * ExceptionTranslationFilter 뒤({@code addFilterBefore(AuthorizationFilter.class)})에 배치해, 전파된 인증 실패를
 * ExceptionTranslationFilter가 authenticationEntryPoint(SecurityErrorHandler)로 위임해 401로 응답하게 한다.
 * 단일 체인이라 prod·비-prod 동일 인가를 적용한다(운영 시크릿은 application-prod.yml에서 env 강제).
 */
@Configuration
public class SecurityConfig {

    /**
     * 단일 SecurityFilterChain — JWT 인증 파이프라인 + 경로별 hasRole 강제 인가(전 프로파일 동일).
     *
     * @throws Exception HttpSecurity 빌드 예외
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, TokenProvider tokenProvider, SecurityErrorHandler securityErrorHandler)
            throws Exception {
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(tokenProvider);

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
                        .requestMatchers("/api/v1/auth/**")
                        .permitAll()
                        // Buyer 셀프가입(Track 34)은 인증 전 접근이므로 POST만 permitAll(GET 등은 anyRequest authenticated로 fail-closed)
                        .requestMatchers(HttpMethod.POST, "/api/v1/users")
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
                        .requestMatchers("/api/v1/cart/**")
                        .hasRole("BUYER")
                        .requestMatchers("/api/v1/order-items/**")
                        .hasRole("SELLER")
                        // 일반 주문 배송 완료(Track 43·Seller). /api/v1/admin/deliveries/**(ADMIN)와 prefix 상이·미충돌.
                        .requestMatchers("/api/v1/deliveries/**")
                        .hasRole("SELLER")
                        .requestMatchers("/api/v1/seller/**")
                        .hasRole("SELLER")
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                // ETF 뒤 배치 — 필터가 전파한 AuthenticationException을 ETF가 잡아 401 위임(JwtAuthenticationFilter Javadoc 참조)
                .addFilterBefore(jwtAuthenticationFilter, AuthorizationFilter.class);
        return http.build();
    }
}

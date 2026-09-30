package com.zslab.mall.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Spring Security 인가 설정(Track 31 Phase 3·Track 33 P5 Stub→JWT 단일 체인). JWT 인증 파이프라인(필터 → 검증 →
 * SecurityContext)이 채운 액터 권한(ROLE_BUYER/SELLER/ADMIN)으로 경로별 hasRole 인가를 강제한다.
 *
 * <p><b>인가 규칙(matcher 순서 = 구체 먼저·first-match)</b>: {@code /api/v1/claims/**}는 BUYER 광범위 규칙만 둔다(셀러 처리
 * endpoint·SELLER 매처는 Track 92에서 제거). permitAll(webhooks·actuator·error·auth) 외 나머지는 {@code authenticated()}로 fail-closed다.
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
     * 구매자 클레임 첨부 서빙 경로(Track 82 D-176·D-235 PR3 K5). 요청 URI를 RequestPath로 파싱해 세그먼트별 디코딩 값으로 매칭하므로
     * {@code %63laims} 같은 인코딩 경로도 동일하게 판정된다(D-233 · ClaimAttachmentServingIntegrationTest 인코딩 경로 케이스).
     */
    private static final RequestMatcher CLAIM_ATTACHMENT_SERVING_MATCHER =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/files/claims/**");

    /**
     * 단일 SecurityFilterChain — JWT 인증 파이프라인 + 경로별 hasRole 강제 인가(전 프로파일 동일).
     *
     * @throws Exception HttpSecurity 빌드 예외
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, TokenProvider tokenProvider, AuthenticatedUserStateVerifier userStateVerifier,
            SecurityErrorHandler securityErrorHandler) throws Exception {
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(tokenProvider, userStateVerifier);

        // CSRF(D-235·PR3 K7): 역할 로그인과, 쿠키가 자격증명이 되는 unsafe 요청을 보호한다(AuthCookies#requiresCsrfProtection). 토큰은 SPA 방식
        // (XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)이고 로그인 전 토큰은 GET /api/v1/auth/csrf로 받는다.
        http.csrf(csrf -> csrf.spa()
                        .requireCsrfProtectionMatcher(AuthCookies::requiresCsrfProtection))
                // 프레임워크 기본 /logout은 쓰지 않는다(D-235 S6·역할별 /auth/logout만). CSRF 활성 시 매처가 바뀌는 부수 변화도 함께 없앤다.
                .logout(AbstractHttpConfigurer::disable)
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
                        // 역할 로그인·로그아웃(D-235)은 인증 전·만료 후에도 호출되므로 역할 접두사 hasRole 규칙보다 앞에서 permitAll
                        .requestMatchers("/api/v1/seller/auth/**", "/api/v1/admin/auth/**")
                        .permitAll()
                        // Buyer 셀프가입(Track 34)은 인증 전 접근이므로 POST만 permitAll(GET 등은 anyRequest authenticated로 fail-closed)
                        .requestMatchers(HttpMethod.POST, "/api/v1/users")
                        .permitAll()
                        // 구매자 상품 카탈로그(Track 44)는 공개 조회이므로 GET만 permitAll(다른 verb는 신설 없음·anyRequest authenticated)
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**")
                        .permitAll()
                        // 공개 카테고리 목록(Track 72)은 공개 taxonomy 조회이므로 GET 단일 경로만 permitAll
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories")
                        .permitAll()
                        // 구매자 채팅 도우미 FAQ(Track 106-3)는 비로그인 열람이므로 목록·즉시 답 두 경로만 정확 매칭 GET permitAll(와일드카드 없음)
                        .requestMatchers(HttpMethod.GET, "/api/v1/faqs", "/api/v1/faqs/suggest")
                        .permitAll()
                        // 클레임 첨부 서빙(Track 82 D-176)은 구매자 경로다(D-235 PR3 K5) — 아래 GET /api/v1/files/** permitAll보다 앞에 둬 익명은
                        // 401. 셀러·관리자는 역할 별칭(/api/v1/{seller|admin}/files/claims/**)을 쓰고, 열람 권한 없음·미존재는 컨트롤러가 404로 통일한다.
                        .requestMatchers(CLAIM_ATTACHMENT_SERVING_MATCHER)
                        .hasRole("BUYER")
                        // 업로드 이미지 서빙(Track 77)은 상품 이미지 공개 조회이므로 GET만 permitAll(업로드는 /api/v1/admin/** ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/files/**")
                        .permitAll()
                        // 광범위 규칙:
                        .requestMatchers("/api/v1/orders/**")
                        .hasRole("BUYER")
                        .requestMatchers("/api/v1/claims/**")
                        .hasRole("BUYER")
                        .requestMatchers("/api/v1/cart/**")
                        .hasRole("BUYER")
                        // 리뷰 쓰기·사진 업로드·도움됐어요(Track 106-1). 공개 조회는 위 GET /api/v1/products/** permitAll에 있다.
                        .requestMatchers("/api/v1/reviews/**")
                        .hasRole("BUYER")
                        // 상품 질문 등록·수정·삭제·내 질문(Track 106-2). 공개 목록·즉시 답은 위 GET /api/v1/products/** permitAll에 있다.
                        .requestMatchers("/api/v1/product-questions/**")
                        .hasRole("BUYER")
                        // 운영자 문의 등록·수정·삭제·답변 확인·내 문의(Track 106-4). 관리자 답변은 /api/v1/admin/** ADMIN.
                        .requestMatchers("/api/v1/inquiries/**")
                        .hasRole("BUYER")
                        // mock 결제 콜백(Track 93 D-198)은 구매자 본인 주문 한정이라 BUYER 단일 경로만(실 PG 콜백은 /api/webhooks/** permitAll 별도)
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/mock-callback")
                        .hasRole("BUYER")
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

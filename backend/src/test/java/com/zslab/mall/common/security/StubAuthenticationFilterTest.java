package com.zslab.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * {@link StubAuthenticationFilter} 단위 테스트(Track 31 Phase 1). 실 Spring 컨텍스트·DB 없이 필터를 직접 구동해
 * {@code "Stub <role>:<id>"} 파싱 → SecurityContext 저장과, 미헤더·비-Stub 헤더의 무영향 통과(기존 550 테스트
 * 무회귀 보장)를 검증한다.
 */
class StubAuthenticationFilterTest {

    private final AuthenticationManager authenticationManager =
            new ProviderManager(new StubAuthenticationProvider());
    private final StubAuthenticationFilter filter =
            new StubAuthenticationFilter(
                    authenticationManager, new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Stub buyer:100 → SecurityContext에 ROLE_BUYER·principal=100 저장")
    void buyerHeader_populatesContext() throws ServletException, IOException {
        MockFilterChain chain = invoke(authorizationRequest("Stub buyer:100"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo(100L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_BUYER");
        assertThat(chain.getRequest()).isNotNull(); // 체인 진행됨
    }

    @Test
    @DisplayName("Stub seller:5 → ROLE_SELLER·principal=5")
    void sellerHeader_populatesContext() throws ServletException, IOException {
        invoke(authorizationRequest("Stub seller:5"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(5L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_SELLER");
    }

    @Test
    @DisplayName("Stub admin:3 → ROLE_ADMIN·principal=3")
    void adminHeader_populatesContext() throws ServletException, IOException {
        invoke(authorizationRequest("Stub admin:3"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(3L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("Authorization 헤더 없음 → SecurityContext 비어있음·체인 통과")
    void noHeader_passesThrough() throws ServletException, IOException {
        MockFilterChain chain = invoke(new MockHttpServletRequest());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("비-Stub 헤더(X-Buyer-Id 상당) → 무시·체인 통과 (기존 stub 헤더 테스트 무영향)")
    void nonStubHeader_ignoredAndPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Buyer-Id", "1"); // 기존 550 테스트 스타일 — Authorization:Stub 미전송

        MockFilterChain chain = invoke(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest authorizationRequest(String authorizationValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, authorizationValue);
        return request;
    }

    private MockFilterChain invoke(MockHttpServletRequest request)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain;
    }
}

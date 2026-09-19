package com.zslab.mall.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.JwtAuthenticationToken;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.exception.SellerSuspendedException;
import com.zslab.mall.seller.repository.SellerMembershipProjection;
import com.zslab.mall.seller.repository.SellerUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link HeaderSellerActorResolver} 셀러 상태 가드 단위 테스트(Track 90-A). SecurityContext principal + repository mock으로
 * 상태별 판정과 매핑 부재 401을 고정한다. 범위: ACTIVE·SUSPENDED는 6개 메서드(GET/HEAD 조회·POST/PUT/PATCH/DELETE 쓰기) 전부 /
 * PENDING·TERMINATED는 GET + 대표 쓰기(POST)만 — 이 두 상태는 {@code isSessionAllowed}가 메서드 판정보다 먼저 차단하므로
 * 메서드 전수 반복은 같은 경로를 중복 실행할 뿐이다(외부 검토 R2·매트릭스 확장 기각).
 */
class HeaderSellerActorResolverTest {

    private static final long USER_ID = 77L;
    private static final long SELLER_ID = 88L;

    private SellerUserRepository sellerUserRepository;
    private HeaderSellerActorResolver resolver;

    @BeforeEach
    void setUp() {
        sellerUserRepository = mock(SellerUserRepository.class);
        resolver = new HeaderSellerActorResolver(sellerUserRepository);
        SecurityContextHolder.getContext()
                .setAuthentication(JwtAuthenticationToken.authenticated(USER_ID, ActorRole.SELLER));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0} 셀러 + GET → sellerId 반환")
    @EnumSource(value = SellerStatus.class, names = {"ACTIVE", "SUSPENDED"})
    @DisplayName("세션 허용 상태(ACTIVE·SUSPENDED) + 조회 → 통과")
    void sessionAllowed_read_returnsSellerId(SellerStatus status) {
        stubMembership(status);

        assertThat(resolver.resolve(request("GET"))).isEqualTo(SELLER_ID);
        assertThat(resolver.resolve(request("HEAD"))).isEqualTo(SELLER_ID);
    }

    @ParameterizedTest(name = "ACTIVE + {0} → sellerId 반환")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("ACTIVE + 쓰기 → 통과")
    void active_write_returnsSellerId(String method) {
        stubMembership(SellerStatus.ACTIVE);

        assertThat(resolver.resolve(request(method))).isEqualTo(SELLER_ID);
    }

    @ParameterizedTest(name = "SUSPENDED + {0} → 403 SellerSuspendedException")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("SUSPENDED + 쓰기 → SellerSuspendedException(403)")
    void suspended_write_throwsSuspended(String method) {
        stubMembership(SellerStatus.SUSPENDED);

        assertThatThrownBy(() -> resolver.resolve(request(method)))
                .isInstanceOf(SellerSuspendedException.class);
    }

    @ParameterizedTest(name = "{0} 셀러 + GET/POST → 401 UnauthenticatedException")
    @EnumSource(value = SellerStatus.class, names = {"PENDING", "TERMINATED"})
    @DisplayName("세션 불가 상태(PENDING·TERMINATED) → GET·대표 쓰기(POST) 모두 UnauthenticatedException(401)·메서드보다 먼저 차단")
    void sessionDenied_anyMethod_throwsUnauthenticated(SellerStatus status) {
        stubMembership(status);

        assertThatThrownBy(() -> resolver.resolve(request("GET"))).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> resolver.resolve(request("POST"))).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    @DisplayName("seller_user 매핑 없음(또는 seller 부재·soft-delete) → UnauthenticatedException(401·fail-closed)")
    void noMembership_throwsUnauthenticated() {
        when(sellerUserRepository.findMembershipByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(request("GET"))).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    @DisplayName("SecurityContext principal 없음 → UnauthenticatedException(401)·repository 미조회")
    void noPrincipal_throwsUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> resolver.resolve(request("GET"))).isInstanceOf(UnauthenticatedException.class);
    }

    private void stubMembership(SellerStatus status) {
        SellerMembershipProjection membership = mock(SellerMembershipProjection.class);
        when(membership.getSellerId()).thenReturn(SELLER_ID);
        when(membership.getStatus()).thenReturn(status);
        when(sellerUserRepository.findMembershipByUserId(USER_ID)).thenReturn(Optional.of(membership));
    }

    private static MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/api/v1/seller/test");
    }
}

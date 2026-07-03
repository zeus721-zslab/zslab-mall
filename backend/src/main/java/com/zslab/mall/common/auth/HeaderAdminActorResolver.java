package com.zslab.mall.common.auth;

import com.zslab.mall.common.exception.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * SecurityContext 기반 Admin 액터 식별자 해석(Track 31 Phase 3). Stub 인증 필터가 채운 principal(actorId·BIGINT)을
 * 반환한다. Phase 1까지의 {@code X-Admin-Id} 헤더 파싱 stub(D-93 Q1 α)은 SecurityContext 조회로 대체됐다(클래스명은
 * 레거시 유지·rename 이연).
 *
 * <p>인증된 액터가 없으면 401({@link UnauthenticatedException}). 자격증명 형식 오류는 상위 {@code StubAuthenticationFilter}가
 * 401로 선처리하므로 본 resolver의 400 경로는 없다. {@link HeaderSellerActorResolver}와 1:1 패턴이며 request 인자는 미사용이다.
 */
@Component
public class HeaderAdminActorResolver implements AdminActorResolver {

    @Override
    public Long resolve(HttpServletRequest request) {
        return SecurityContextActorSupport.requireActorId();
    }
}

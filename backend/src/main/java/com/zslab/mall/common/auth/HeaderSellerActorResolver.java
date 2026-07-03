package com.zslab.mall.common.auth;

import com.zslab.mall.common.exception.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * SecurityContext 기반 Seller 액터 식별자 해석(Track 31 Phase 3). JwtAuthenticationFilter가 채운 principal(actorId·BIGINT)을
 * 반환한다. Phase 1까지의 {@code X-Seller-Id} 헤더 파싱 stub(D-92 Q1 α′)은 SecurityContext 조회로 대체됐다(클래스명은
 * 레거시 유지·rename 이연).
 *
 * <p>인증된 액터가 없으면 401({@link UnauthenticatedException}). 자격증명 형식 오류는 상위 {@code JwtAuthenticationFilter}가
 * 401로 선처리하므로 본 resolver의 400 경로는 없다. {@code resolve(HttpServletRequest)} 시그니처는 인터페이스·호출부
 * 호환을 위해 유지하되 request 인자는 미사용이다.
 */
@Component
public class HeaderSellerActorResolver implements SellerActorResolver {

    @Override
    public Long resolve(HttpServletRequest request) {
        return SecurityContextActorSupport.requireActorId();
    }
}

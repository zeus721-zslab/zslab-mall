package com.zslab.mall.common.auth;

import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.seller.repository.SellerUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * SecurityContext 기반 Seller 액터 식별자 해석(Track 31 Phase 3·Track 36 γ Phase 2). JwtAuthenticationFilter가 채운
 * principal(user.id·BIGINT)을 seller_user 매핑으로 seller.id에 단건 해소해 반환한다. user_id 단독 UNIQUE(V12)로
 * user.id→seller.id는 최대 1건이다. Phase 1까지의 {@code X-Seller-Id} 헤더 파싱 stub(D-92 Q1 α′)은 SecurityContext
 * 조회로 대체됐고(클래스명은 레거시 유지·rename 이연), Track 36 γ에서 user.id passthrough 결함을 실 매핑으로 교정했다.
 *
 * <p>인증된 액터가 없으면 401({@link UnauthenticatedException}). 인증됐으나 연결된 seller_user 매핑이 없으면(예: 세션 중
 * 판매자 자격 회수) 동일 {@link UnauthenticatedException}으로 fail-closed 401 처리한다(새 예외 타입 미신설·resolver 단일
 * 실패 계약 유지). 자격증명 형식 오류는 상위 {@code JwtAuthenticationFilter}가 401로 선처리하므로 본 resolver의 400 경로는
 * 없다. {@code resolve(HttpServletRequest)} 시그니처는 인터페이스·호출부 호환을 위해 유지하되 request 인자는 미사용이다.
 */
@Component
public class HeaderSellerActorResolver implements SellerActorResolver {

    private final SellerUserRepository sellerUserRepository;

    public HeaderSellerActorResolver(SellerUserRepository sellerUserRepository) {
        this.sellerUserRepository = sellerUserRepository;
    }

    @Override
    public Long resolve(HttpServletRequest request) {
        Long userId = SecurityContextActorSupport.requireActorId();
        return sellerUserRepository.findSellerIdByUserId(userId)
                .orElseThrow(() -> new UnauthenticatedException("인증된 판매자를 확인할 수 없습니다"));
    }
}
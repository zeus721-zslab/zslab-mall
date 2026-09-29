package com.zslab.mall.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Buyer 액터 식별자 해석(Track 31 Phase 3 신설). {@link SellerActorResolver}·{@link AdminActorResolver}와 대칭 패턴이며
 * SecurityContext 기반 구현체가 principal(actorId·BIGINT)을 반환한다. Phase 1까지 BuyerOrderController/BuyerClaimController에
 * 인라인이던 {@code resolveBuyerId}를 별도 resolver로 승격한 것이다(D-39 seam 정리).
 */
public interface BuyerActorResolver {

    /**
     * 요청에서 Buyer 액터 식별자(BIGINT)를 해석한다.
     *
     * @param request 현재 HTTP 요청(SecurityContext 조회로 대체되어 미사용·시그니처 호환 목적 보존)
     * @return Buyer 액터 식별자(BIGINT)
     * @throws com.zslab.mall.common.exception.UnauthenticatedException 인증된 액터가 없는 경우(401)
     */
    Long resolve(HttpServletRequest request);

    /**
     * 공개 경로에서 로그인한 구매자면 식별자, 익명이면 empty(Track 106-1·리뷰 목록의 "내가 도움됐어요 눌렀는지"). 필터는 경로에 맞는 역할 쿠키만
     * 읽으므로 {@code /api/v1/**} 공개 경로의 인증 주체는 구매자뿐이다.
     */
    Optional<Long> find();
}

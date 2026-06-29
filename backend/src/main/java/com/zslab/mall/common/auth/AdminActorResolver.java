package com.zslab.mall.common.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Admin 액터 식별자 해석. {@code X-Admin-Id} 헤더 stub은 D-93 Q1 α 임시 seam이다.
 *
 * <p>Spring Security 도입 시 동일 인터페이스로 SecurityContext 기반 구현체로 교체할 수 있다.
 * 본 인터페이스는 {@link SellerActorResolver}와 공유하지 않고 별도로 분리한다(D-93 Q2·SellerActorResolver
 * Javadoc "후속 Admin/Buyer 액터 resolver는 별도 인터페이스로 분리" 명시 지침 정합). 후속 Buyer 액터 resolver도
 * 별도 인터페이스로 분리한다.
 */
public interface AdminActorResolver {

    /**
     * 요청에서 Admin 액터 식별자(BIGINT)를 해석한다.
     *
     * @param request 현재 HTTP 요청
     * @return Admin 액터 식별자(BIGINT)
     * @throws com.zslab.mall.common.exception.UnauthenticatedException 식별 헤더가 없는 경우(401)
     * @throws com.zslab.mall.common.exception.MalformedRequestException 식별자 형식이 올바르지 않은 경우(400)
     */
    Long resolve(HttpServletRequest request);
}

package com.zslab.mall.common.auth;

import com.zslab.mall.common.exception.UnauthenticatedException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityContext에서 인증된 액터 식별자(BIGINT)를 꺼내는 공통 헬퍼(Track 31 Phase 3). Seller/Admin/Buyer resolver가
 * 공유한다. JwtAuthenticationFilter가 채운 principal(actorId·Long)만 유효하며, 부재·비-Long(익명 등)은 미인증으로 본다.
 */
final class SecurityContextActorSupport {

    private SecurityContextActorSupport() {}

    /**
     * SecurityContext의 principal을 액터 식별자로 반환한다.
     *
     * @throws UnauthenticatedException 인증된 액터가 없는 경우(principal 부재·비-Long·401 상당)
     */
    static Long requireActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = (authentication != null) ? authentication.getPrincipal() : null;
        if (!(principal instanceof Long actorId)) {
            throw new UnauthenticatedException("인증된 액터가 없습니다");
        }
        return actorId;
    }

    /**
     * 인증된 액터가 있으면 식별자, 없으면(익명·principal 비-Long) empty. 공개 경로에서 로그인 여부에 따라 응답만 달라지는 조회용(Track 106-1).
     */
    static Optional<Long> findActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = (authentication != null) ? authentication.getPrincipal() : null;
        if (!(principal instanceof Long actorId)) {
            return Optional.empty();
        }
        return Optional.of(actorId);
    }
}

package com.zslab.mall.common.auth;

import com.zslab.mall.common.exception.UnauthenticatedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SecurityContext에서 인증된 액터의 coarse 역할 문자열(ADMIN·SELLER·BUYER)을 해석한다(Track 52 Phase 2·감사 actor_role용).
 *
 * <p>{@link com.zslab.mall.common.security.JwtAuthenticationToken}은 principal(actorId)에 role을 담지 않고 권한을
 * {@code ROLE_<ActorRole>} 단건으로만 부여한다(§8 recon). 따라서 감사 로그의 actor_role은 authority에서 {@code ROLE_}
 * 프리픽스를 제거해 취득한다(coarse 그대로 저장·세분 RoleCode DB 조회 안 함·결정5).
 */
@Component
public class ActorRoleResolver {

    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * 현재 인증 액터의 coarse 역할 문자열을 반환한다(예: {@code "ADMIN"}).
     *
     * @throws UnauthenticatedException 인증 액터·권한이 없는 경우(401 상당)
     */
    public String requireCoarseRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().isEmpty()) {
            throw new UnauthenticatedException("인증된 액터 권한이 없습니다");
        }
        GrantedAuthority authority = authentication.getAuthorities().iterator().next();
        String value = authority.getAuthority();
        return value.startsWith(ROLE_PREFIX) ? value.substring(ROLE_PREFIX.length()) : value;
    }
}

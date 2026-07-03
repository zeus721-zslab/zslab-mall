package com.zslab.mall.common.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Stub 인증 토큰(Track 31 Phase 1). principal은 actorId(BIGINT), 권한은 {@code ROLE_<ROLE>} 단건이다.
 * 미인증(필터 생성)·인증완료(provider 생성) 두 상태를 표준 관례(UsernamePasswordAuthenticationToken 방식)대로 표현한다.
 */
public class StubAuthenticationToken extends AbstractAuthenticationToken {

    private final StubRole role;
    private final Long actorId;

    /** 미인증 토큰 — 필터가 생성해 AuthenticationManager에 위임한다. */
    public StubAuthenticationToken(StubRole role, Long actorId) {
        super(null);
        this.role = role;
        this.actorId = actorId;
        setAuthenticated(false);
    }

    private StubAuthenticationToken(
            StubRole role, Long actorId, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.role = role;
        this.actorId = actorId;
        setAuthenticated(true);
    }

    /** 인증 완료 토큰 — provider가 role 기반 단일 권한(ROLE_ 프리픽스)으로 생성한다. */
    public static StubAuthenticationToken authenticated(StubRole role, Long actorId) {
        return new StubAuthenticationToken(
                role, actorId, List.of(new SimpleGrantedAuthority(role.authority())));
    }

    public StubRole getRole() {
        return role;
    }

    @Override
    public Object getPrincipal() {
        return actorId;
    }

    @Override
    public Object getCredentials() {
        return null; // Stub — credential 미검증
    }
}

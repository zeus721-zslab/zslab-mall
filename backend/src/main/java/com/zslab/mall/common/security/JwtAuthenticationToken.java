package com.zslab.mall.common.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * JWT 인증 완료 토큰(Track 33 P5). principal은 actorId(BIGINT), 권한은 {@code ROLE_<ROLE>} 단건이다.
 * {@link JwtAuthenticationFilter}가 {@link TokenProvider#verify}로 검증한 토큰에서 직접 생성한다.
 *
 * <p>기존 인증 토큰과 동일한 SecurityContext 계약(principal=Long·authority=ROLE_xxx 단건)을 재현해
 * {@link com.zslab.mall.common.auth.SecurityContextActorSupport}·resolver 3종이 무수정 동작한다. JWT는 필터가
 * 직접 인증하므로 미인증 상태·AuthenticationProvider가 불필요하다(인증 완료 토큰만 존재).
 */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final Long actorId;
    private final boolean publicDemo;

    private JwtAuthenticationToken(Long actorId, ActorRole role, boolean publicDemo) {
        super(List.of(new SimpleGrantedAuthority(role.authority())));
        this.actorId = actorId;
        this.publicDemo = publicDemo;
        setAuthenticated(true);
    }

    /** 인증 완료 토큰 — 필터가 role 기반 단일 권한(ROLE_ 프리픽스)으로 생성한다. 데모 표식 없음. */
    public static JwtAuthenticationToken authenticated(Long actorId, ActorRole role) {
        return new JwtAuthenticationToken(actorId, role, false);
    }

    /** @param publicDemo 공개 관리자 데모 세션 표식(최종 점검 K1 · {@link PublicDemoSessionGuard}가 읽는다) */
    public static JwtAuthenticationToken authenticated(Long actorId, ActorRole role, boolean publicDemo) {
        return new JwtAuthenticationToken(actorId, role, publicDemo);
    }

    public boolean isPublicDemo() {
        return publicDemo;
    }

    @Override
    public Object getPrincipal() {
        return actorId;
    }

    @Override
    public Object getCredentials() {
        return null; // JWT — 검증은 서명으로 완료·credential 미보관
    }
}

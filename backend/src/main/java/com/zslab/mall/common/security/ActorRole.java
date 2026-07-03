package com.zslab.mall.common.security;

/**
 * 인증 파이프라인(JWT·Track 33)이 다루는 액터 역할 3종. 인증 토큰의 role과 Spring Security 권한(ROLE_ 프리픽스)을 잇는다.
 *
 * <p>DB 영속 대상이 아니다 — auth 도메인의 {@link com.zslab.mall.auth.enums.RoleCode}(SUPER_ADMIN·SELLER_OWNER 등
 * 세분 RBAC 코드·DDL ENUM)와는 별개의 coarse 액터 구분이며, 인메모리 인증에만 쓰여 4층위 ENUM 잠금 대상이 아니다.
 */
public enum ActorRole {
    BUYER,
    SELLER,
    ADMIN;

    /** Spring Security GrantedAuthority 문자열(ROLE_ 프리픽스·{@code hasRole} 매칭용). */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * 헤더 role 토큰(대소문자 무시)을 파싱한다.
     *
     * @throws IllegalArgumentException 알 수 없는 role 토큰인 경우
     */
    public static ActorRole fromToken(String token) {
        return ActorRole.valueOf(token.trim().toUpperCase());
    }
}

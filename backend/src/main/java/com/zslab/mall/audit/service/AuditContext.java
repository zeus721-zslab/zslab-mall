package com.zslab.mall.audit.service;

/**
 * 감사 적재 시점의 행위자 컨텍스트 불변 값객체(Track 52 Phase 1). {@link AuditRecorder}가 AuditLog.actor_* 컬럼으로 조립한다.
 *
 * <p><b>필수/선택(D 결정4·결정5)</b>: {@code actorUserId}·{@code actorRole}은 필수(null 금지)다. {@code actorRole}은
 * coarse 액터 구분(ADMIN·SELLER·BUYER) 문자열을 그대로 담는다(세분 RoleCode DB 조회 안 함). {@code ipAddress}·
 * {@code userAgent}는 이번 트랙 미수집이라 nullable을 허용하되 필드는 둔다(후행 수집 확장 여지·수집 코드는 신설 안 함).
 *
 * <p><b>시스템 행위자(Track 96-6)</b>: 스케줄러 등 요청 컨텍스트가 없는 자동 실행은 {@link #system()}으로 만든다 —
 * {@code actorUserId=null}·{@code actorRole=}{@value #SYSTEM_ACTOR_ROLE}. actorUserId null 허용은 이 역할에 한정되며 그 외 경로의
 * null 가드는 그대로다(audit_log.actor_user_id는 DDL상 NULL 허용·FK 없음).
 */
public record AuditContext(Long actorUserId, String actorRole, String ipAddress, String userAgent) {

    /** 요청 컨텍스트 없는 자동 실행(스케줄러)의 행위자 역할. coarse 역할(ADMIN·SELLER·BUYER)과 구분된다. */
    public static final String SYSTEM_ACTOR_ROLE = "SYSTEM";

    public AuditContext {
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("AuditContext actorRole은 null·blank일 수 없습니다.");
        }
        if (actorUserId == null && !SYSTEM_ACTOR_ROLE.equals(actorRole)) {
            throw new IllegalArgumentException("AuditContext actorUserId는 null일 수 없습니다.");
        }
    }

    /** 시스템 행위자 컨텍스트(actorUserId null·role SYSTEM·ip/UA 없음). 스케줄러 자동 실행의 감사 적재용. */
    public static AuditContext system() {
        return new AuditContext(null, SYSTEM_ACTOR_ROLE, null, null);
    }

    /** ip/UA 없이 행위자만으로 컨텍스트를 만든다(이번 트랙 표준 경로·결정4). */
    public static AuditContext of(Long actorUserId, String actorRole) {
        return new AuditContext(actorUserId, actorRole, null, null);
    }

    /** ip/UA를 함께 담아 컨텍스트를 만든다(후행 수집 도입 시 확장 진입점). */
    public static AuditContext of(Long actorUserId, String actorRole, String ipAddress, String userAgent) {
        return new AuditContext(actorUserId, actorRole, ipAddress, userAgent);
    }
}

package com.zslab.mall.audit.enums;

/**
 * 감사 로그 행위 유형(audit_log.action DDL ENUM 1:1 매핑·A#18·4층위 잠금 DB 레이어 완료).
 *
 * <p>DTO @ValidEnum·프론트 constants는 Track 8+ 이연(D-86 §OUT-OF-SCOPE).
 */
public enum AuditLogAction {

    CREATE,
    UPDATE,
    DELETE,
    APPROVE,
    REJECT,
    LOGIN,
    LOGOUT
}

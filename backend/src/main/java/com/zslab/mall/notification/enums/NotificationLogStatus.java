package com.zslab.mall.notification.enums;

/**
 * 알림 발송 상태(notification_log.status DDL ENUM 1:1 매핑·A#17·4층위 잠금 DB 레이어 완료).
 *
 * <p>PENDING→SENT 전이 핸들러는 Track 8+ 이연(D-86 §OUT-OF-SCOPE).
 * DTO @ValidEnum·프론트 constants는 Track 8+ 이연.
 */
public enum NotificationLogStatus {

    PENDING,
    SENT,
    FAILED
}

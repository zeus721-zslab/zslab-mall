package com.zslab.mall.notification.enums;

/**
 * 알림 발송 채널(notification_log.channel DDL ENUM 1:1 매핑·A#16·4층위 잠금 DB 레이어 완료).
 *
 * <p>DTO @ValidEnum·프론트 constants는 Track 8+ 이연(D-86 §OUT-OF-SCOPE).
 */
public enum NotificationChannel {

    EMAIL,
    SMS,
    PUSH,
    IN_APP
}

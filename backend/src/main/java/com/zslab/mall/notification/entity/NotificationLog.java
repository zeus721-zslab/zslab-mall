package com.zslab.mall.notification.entity;

import com.zslab.mall.common.entity.AbstractCreatedOnlyEntity;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.notification.enums.NotificationChannel;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 로그(Infra/Event Processing·ARCHIVE·append-only·public_id 없음).
 *
 * <p>D-18·D-01 — Aggregate 아님·도메인 트랜잭션 주체 아님. 이벤트(E1·E2·E4·E5·E9·E10) 발송 이력 기록.
 * {@link AbstractCreatedOnlyEntity} 직접 상속(created_at·created_by 2컬럼·D-86 Q3).
 * public_id 없음(DDL 실측·recon §1.6 확인). recipient_user_id·target_type/target_id는 FK 없는 논리참조(D-01).
 * PENDING→SENT 전이 핸들러·DTO @ValidEnum은 Track 8+ 이연(D-86 §OUT-OF-SCOPE).
 */
@Entity
@Table(name = "notification_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog extends AbstractCreatedOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private NotificationChannel channel;

    @Column(name = "template_code", nullable = false, length = 100)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50)
    private PolymorphicTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationLogStatus status;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "failed_reason", columnDefinition = "TEXT")
    private String failedReason;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static NotificationLog create(
            Long recipientUserId,
            NotificationChannel channel,
            String templateCode,
            PolymorphicTargetType targetType,
            Long targetId,
            String title,
            String content) {
        if (channel == null || templateCode == null || templateCode.isBlank()
                || targetType == null || targetId == null) {
            throw new IllegalArgumentException(
                    "NotificationLog 필수값 누락(channel·templateCode·targetType·targetId).");
        }
        NotificationLog log = new NotificationLog();
        log.recipientUserId = recipientUserId;
        log.channel = channel;
        log.templateCode = templateCode;
        log.targetType = targetType;
        log.targetId = targetId;
        log.title = title;
        log.content = content;
        log.status = NotificationLogStatus.PENDING;
        return log;
    }
}

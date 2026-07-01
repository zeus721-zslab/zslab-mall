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

    /**
     * 발송 성공 전이(Track 19·D-86 §후속 종결). PENDING → SENT로 전이하고 발송 시각을 기록한다.
     * {@link AbstractCreatedOnlyEntity} Javadoc "status 전이 허용"(A2 결정) 근거로 append-only 위에서 UPDATE를 수행한다.
     *
     * @param sentAt 발송 완료 시각
     * @throws IllegalStateException PENDING이 아닌 상태에서 호출 시(중복 전이 방지)
     */
    public void markSent(LocalDateTime sentAt) {
        if (this.status != NotificationLogStatus.PENDING) {
            throw new IllegalStateException(
                    "NotificationLog 발송 전이 불가: PENDING에서만 SENT 전이 허용(현재=" + this.status + ").");
        }
        this.status = NotificationLogStatus.SENT;
        this.sentAt = sentAt;
    }

    /**
     * 발송 실패 전이(Track 19·D-86 §후속 종결). PENDING → FAILED로 전이하고 실패 사유를 기록한다.
     * {@link AbstractCreatedOnlyEntity} Javadoc "status 전이 허용"(A2 결정) 근거로 append-only 위에서 UPDATE를 수행한다.
     *
     * @param reason 발송 실패 사유
     * @throws IllegalStateException PENDING이 아닌 상태에서 호출 시(중복 전이 방지)
     */
    public void markFailed(String reason) {
        if (this.status != NotificationLogStatus.PENDING) {
            throw new IllegalStateException(
                    "NotificationLog 실패 전이 불가: PENDING에서만 FAILED 전이 허용(현재=" + this.status + ").");
        }
        this.status = NotificationLogStatus.FAILED;
        this.failedReason = reason;
    }
}

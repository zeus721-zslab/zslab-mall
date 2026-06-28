package com.zslab.mall.audit.entity;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.common.entity.AbstractCreatedOnlyEntity;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.util.PublicIdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 감사 로그(AUD Aggregate Root·ARCHIVE·append-only·public_id {@code aud_}).
 *
 * <p>{@link AbstractCreatedOnlyEntity} 상속(created_at·created_by 2컬럼만·updated_at/by 없음). publicId 필드와
 * {@code @PrePersist}는 AbstractPublicIdFullAuditableEntity Javadoc 기명시 패턴에 따라 본 클래스에 자체 정의한다
 * (public_id+append-only 전용 base 미신설·단일 사용 추상화 회피·D-86 Q2).
 *
 * <p>diff_json은 String(LONGTEXT) 매핑 — JSON_VALID는 DDL CHECK 강제·Entity 레이어 JSON 구조 분석 미수행
 * (D-86 Q5·D-11). JSON 함수 질의는 native query 위임. 민감정보 마스킹·AuditLog 적재 훅은 Track 8+ 이연.
 * actor_user_id·target_type/target_id는 FK 없는 논리참조(D-01 polymorphic).
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog extends AbstractCreatedOnlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @EqualsAndHashCode.Include
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 30, nullable = false, updatable = false)
    private String publicId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_role", length = 50)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AuditLogAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50)
    private PolymorphicTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "diff_json", columnDefinition = "LONGTEXT")
    private String diffJson;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @PrePersist
    private void generatePublicId() {
        if (publicId == null) {
            publicId = PublicIdGenerator.generate("aud");
        }
    }

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static AuditLog create(
            Long actorUserId,
            String actorRole,
            AuditLogAction action,
            PolymorphicTargetType targetType,
            Long targetId,
            String diffJson,
            String ipAddress,
            String userAgent) {
        if (action == null || targetType == null || targetId == null) {
            throw new IllegalArgumentException("AuditLog 필수값 누락(action·targetType·targetId).");
        }
        AuditLog log = new AuditLog();
        log.actorUserId = actorUserId;
        log.actorRole = actorRole;
        log.action = action;
        log.targetType = targetType;
        log.targetId = targetId;
        log.diffJson = diffJson;
        log.ipAddress = ipAddress;
        log.userAgent = userAgent;
        return log;
    }
}

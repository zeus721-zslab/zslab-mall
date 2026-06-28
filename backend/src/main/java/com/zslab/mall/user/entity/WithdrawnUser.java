package com.zslab.mall.user.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 탈퇴 회원 아카이브(USR 종속·ARCHIVE).
 *
 * <p>originalUser는 User Aggregate 내부 참조 — D-01에 따라 @ManyToOne LAZY 허용.
 * deleted_at 없음(ARCHIVE 분류) — soft-delete 미적용.
 */
@Entity
@Table(name = "withdrawn_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawnUser extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_user_id", nullable = false, updatable = false)
    private User originalUser;

    @Column(name = "withdraw_reason", length = 255)
    private String withdrawReason;

    @Column(name = "legal_retention_until")
    private LocalDateTime legalRetentionUntil;

    @Column(name = "anonymized_at")
    private LocalDateTime anonymizedAt;

    /**
     * @throws IllegalArgumentException originalUser 누락 시
     */
    public static WithdrawnUser create(User originalUser, String withdrawReason, LocalDateTime legalRetentionUntil) {
        if (originalUser == null) {
            throw new IllegalArgumentException("WithdrawnUser 필수값 누락(originalUser).");
        }
        WithdrawnUser withdrawnUser = new WithdrawnUser();
        withdrawnUser.originalUser = originalUser;
        withdrawnUser.withdrawReason = withdrawReason;
        withdrawnUser.legalRetentionUntil = legalRetentionUntil;
        return withdrawnUser;
    }
}

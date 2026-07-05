package com.zslab.mall.user.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import com.zslab.mall.user.enums.GradeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구매자 프로필(USR 종속·SOFT 상속·user_id 공유 PK).
 *
 * <p>user_id는 공유 PK — @MapsId가 user.id를 userId에 자동 채움(@GeneratedValue 없음).
 * gradeId는 BuyerGrade 외부 Aggregate — D-01에 따라 Long 필드만(@ManyToOne 금지).
 * deleted_at 없음 — SOFT 상속(User Root soft-delete 경유).
 */
@Entity
@Table(name = "buyer_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerProfile extends AbstractFullAuditableEntity {

    @Id
    @Column(name = "user_id")
    @EqualsAndHashCode.Include
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "grade_id", nullable = false)
    private Long gradeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade_source", nullable = false)
    private GradeSource gradeSource;

    @Column(name = "grade_locked_until")
    private LocalDateTime gradeLockedUntil;

    @Column(name = "grade_updated_at")
    private LocalDateTime gradeUpdatedAt;

    /**
     * @throws IllegalArgumentException user·gradeId·gradeSource 누락 시
     */
    public static BuyerProfile create(User user, Long gradeId, GradeSource gradeSource) {
        if (user == null || gradeId == null || gradeSource == null) {
            throw new IllegalArgumentException("BuyerProfile 필수값 누락(user·gradeId·gradeSource).");
        }
        BuyerProfile profile = new BuyerProfile();
        profile.user = user;
        profile.gradeId = gradeId;
        profile.gradeSource = gradeSource;
        return profile;
    }

    /**
     * 등급 산정 결과를 반영한다(Track 51). {@code gradeId}·{@code gradeSource}·{@code gradeUpdatedAt}을 갱신한다.
     * grade_locked_until은 본 메서드가 건드리지 않는다(운영자 lock 부여 경로와 책임 분리·D-136).
     *
     * @param gradeId     선택된 등급 id
     * @param gradeSource 산정 출처(AUTO 배치·MANUAL·EVENT)
     * @param updatedAt   등급 변경 시각
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public void applyGrade(Long gradeId, GradeSource gradeSource, LocalDateTime updatedAt) {
        if (gradeId == null || gradeSource == null || updatedAt == null) {
            throw new IllegalArgumentException("BuyerProfile.applyGrade 필수값 누락(gradeId·gradeSource·updatedAt).");
        }
        this.gradeId = gradeId;
        this.gradeSource = gradeSource;
        this.gradeUpdatedAt = updatedAt;
    }
}

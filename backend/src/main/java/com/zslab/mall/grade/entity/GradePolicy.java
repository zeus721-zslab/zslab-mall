package com.zslab.mall.grade.entity;

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
 * 등급 정책(GRD 종속).
 *
 * <p>CHECK(min_amount <= max_amount) 검증은 DB에 위임(V1 DDL·Track 8+ Application 검증 이연).
 * equals/hashCode는 id 기반.
 */
@Entity
@Table(name = "grade_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GradePolicy extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grade_id", nullable = false, updatable = false)
    private BuyerGrade grade;

    @Column(name = "min_amount", nullable = false)
    private Long minAmount;

    @Column(name = "max_amount", nullable = false)
    private Long maxAmount;

    @Column(name = "discount_rate", nullable = false)
    private int discountRate;

    @Column(name = "point_rate", nullable = false)
    private int pointRate;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to", nullable = false)
    private LocalDateTime effectiveTo;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static GradePolicy create(
            BuyerGrade grade,
            Long minAmount,
            Long maxAmount,
            int discountRate,
            int pointRate,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo,
            int version) {
        if (grade == null || minAmount == null || maxAmount == null
                || effectiveFrom == null || effectiveTo == null) {
            throw new IllegalArgumentException("GradePolicy 필수값 누락(grade·minAmount·maxAmount·effectiveFrom·effectiveTo).");
        }
        GradePolicy policy = new GradePolicy();
        policy.grade = grade;
        policy.minAmount = minAmount;
        policy.maxAmount = maxAmount;
        policy.discountRate = discountRate;
        policy.pointRate = pointRate;
        policy.effectiveFrom = effectiveFrom;
        policy.effectiveTo = effectiveTo;
        policy.version = version;
        policy.isActive = true;
        return policy;
    }
}

package com.zslab.mall.grade.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구매자 등급(GRD Aggregate Root).
 *
 * <p>DDL에 deleted_at 없음 — AbstractFullAuditableEntity 적용(recon-report.md §6 WARN-1 정합).
 * equals/hashCode는 id 기반.
 */
@Entity
@Table(name = "buyer_grade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerGrade extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false)
    private BuyerGradeCode code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static BuyerGrade create(BuyerGradeCode code, String name) {
        if (code == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("BuyerGrade 필수값 누락(code·name).");
        }
        BuyerGrade grade = new BuyerGrade();
        grade.code = code;
        grade.name = name;
        return grade;
    }
}

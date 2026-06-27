package com.zslab.mall.code.entity;

import com.zslab.mall.common.entity.AbstractFullAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 코드 그룹(COD Aggregate Root).
 *
 * <p>DDL에 deleted_at 없음 — AbstractFullAuditableEntity 적용(recon-report.md §6 WARN-2 정합).
 * equals/hashCode는 id 기반.
 */
@Entity
@Table(name = "code_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CodeGroup extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static CodeGroup create(String code, String name, String description) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("CodeGroup 필수값 누락(code·name).");
        }
        CodeGroup group = new CodeGroup();
        group.code = code;
        group.name = name;
        group.description = description;
        return group;
    }
}

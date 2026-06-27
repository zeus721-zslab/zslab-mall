package com.zslab.mall.code.entity;

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
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 코드(COD 종속).
 *
 * <p>UK: (group_id, code). is_active·is_system: TINYINT(1) → boolean 원시 타입.
 * equals/hashCode는 id 기반.
 */
@Entity
@Table(name = "code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Code extends AbstractFullAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private CodeGroup group;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    /**
     * @throws IllegalArgumentException 필수값 누락 시
     */
    public static Code create(CodeGroup group, String code, String label, int displayOrder) {
        if (group == null || code == null || code.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("Code 필수값 누락(group·code·label).");
        }
        Code c = new Code();
        c.group = group;
        c.code = code;
        c.label = label;
        c.displayOrder = displayOrder;
        c.isActive = true;
        c.isSystem = false;
        return c;
    }
}

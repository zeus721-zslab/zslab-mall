package com.zslab.mall.category.entity;

import com.zslab.mall.common.entity.AbstractSoftDeletableEntity;
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
import org.hibernate.annotations.SQLRestriction;

/**
 * 카테고리(CAT Aggregate Root·SOFT·self-ref 계층).
 *
 * <p>{@code @SQLRestriction}은 Hibernate 6.6에서 {@code @MappedSuperclass} 선언이 {@code @Entity}로
 * 전파되지 않는 버그로 인해 본 클래스에 직접 선언한다(AbstractSoftDeletableEntity 중복 선언 의도적).
 * parent가 null인 행이 루트 카테고리. equals/hashCode는 id 기반.
 */
@Entity
@Table(name = "category")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends AbstractSoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "depth", nullable = false)
    private int depth;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * parent가 null이면 루트 카테고리를 생성한다.
     *
     * @throws IllegalArgumentException displayName 누락 시
     */
    public static Category create(Category parent, String displayName, int depth, int sortOrder) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Category 필수값 누락(displayName).");
        }
        Category category = new Category();
        category.parent = parent;
        category.displayName = displayName;
        category.depth = depth;
        category.sortOrder = sortOrder;
        return category;
    }
}

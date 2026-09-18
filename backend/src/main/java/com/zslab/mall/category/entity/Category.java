package com.zslab.mall.category.entity;

import com.zslab.mall.common.entity.AbstractSoftDeletableEntity;
import com.zslab.mall.settlement.service.CommissionRateResolver;
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
     * 카테고리 기본 수수료율·basis-point(1000 = 10.00%). NULL=미설정(플랫폼 기본율)·셀러 개별율이 없을 때 적용(Track 85·V30).
     * 편집은 {@link #update}(Track 89-C D-185)·범위 밖 값은 체크아웃 판정({@code CommissionRateResolver})이 주문을 차단하므로 저장 전 검증한다.
     */
    @Column(name = "commission_rate")
    private Integer commissionRate;

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

    /**
     * 관리자 수정(Track 89-C D-185·전체 치환). commissionRate null은 "미설정(플랫폼 기본율)"으로 환원한다.
     *
     * @throws IllegalArgumentException displayName 공백·sortOrder 음수·commissionRate 범위(0~10000 bp) 밖일 때
     */
    public void update(String displayName, int sortOrder, Integer commissionRate) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Category 필수값 누락(displayName).");
        }
        if (sortOrder < 0) {
            throw new IllegalArgumentException("Category sortOrder는 0 이상이어야 합니다. 입력: " + sortOrder);
        }
        if (commissionRate != null) {
            CommissionRateResolver.requireInRange(commissionRate, "Category.commissionRate");
        }
        this.displayName = displayName;
        this.sortOrder = sortOrder;
        this.commissionRate = commissionRate;
    }

    /** 일괄 정렬 변경(Track 89-C)·순서 배열의 index를 그대로 sortOrder로 쓴다. */
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}

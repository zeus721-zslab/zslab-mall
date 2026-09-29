package com.zslab.mall.review.entity;

import com.zslab.mall.common.entity.AbstractSeedEntity;
import com.zslab.mall.review.enums.ReviewKeywordGroup;
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
 * 리뷰 키워드 마스터(Track 106-1·V40). {@code topCategoryId}가 NULL이면 모든 상품에 쓰는 기본 세트이고, 값이 있으면 그 최상위 카테고리
 * 상품에만 추가로 쓰는 세트다. 행은 마이그레이션(기본 세트)·데모 시드(카테고리별 세트)만 넣으므로 생성 메서드를 두지 않는다.
 */
@Entity
@Table(name = "review_keyword")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewKeyword extends AbstractSeedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "label", nullable = false, length = 50)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_code", nullable = false, length = 20)
    private ReviewKeywordGroup groupCode;

    @Column(name = "top_category_id")
    private Long topCategoryId;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}

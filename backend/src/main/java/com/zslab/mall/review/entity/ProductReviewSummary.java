package com.zslab.mall.review.entity;

import com.zslab.mall.common.entity.AbstractAggregateEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품별 리뷰 한 줄 요약(Track 106-1·V40). 리뷰 원천에서 언제든 재계산할 수 있는 파생 행이며 쓰기는 {@code ProductReviewSummaryRepository}의
 * upsert·삭제로만 한다(읽기 전용 매핑). 행이 없으면 "요약 없음"이다.
 */
@Entity
@Table(name = "product_review_summary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReviewSummary extends AbstractAggregateEntity {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "summary_text", nullable = false, length = 500)
    private String summaryText;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;
}

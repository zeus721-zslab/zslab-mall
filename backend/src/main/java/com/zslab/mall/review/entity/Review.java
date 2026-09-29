package com.zslab.mall.review.entity;

import com.zslab.mall.common.entity.AbstractPublicIdSoftDeletableEntity;
import com.zslab.mall.review.enums.ReviewStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 상품 리뷰(RVW Aggregate Root·SOFT·public_id {@code rvw_}·Track 106-1·V40).
 *
 * <p>구매확정 품목당 1개(uk_review_order_item). 다른 Aggregate는 id로만 참조한다(D-01·order_item_id·product_id·buyer_id). 선택 키워드는
 * 리뷰에 종속된 값 집합이라 {@code review_keyword_selection}을 {@link ElementCollection}으로 둔다(키워드 id만·마스터는 별도 Aggregate).
 * {@code product_id}는 {@code order_item.product_id}에서만 채운다(요청값 불가 · 수정으로 바뀌지 않음 — updatable=false). 공개 목록·요약이
 * 상품별로 모으는 기준이라 품목과 어긋나면 다른 상품에 리뷰가 붙는다.
 * {@code helpful_count}는 동시 증감을 원자적 UPDATE로만 바꾸므로(ReviewRepository) 엔티티에 변경 메서드를 두지 않는다.
 *
 * <p>{@code @SQLRestriction}은 Hibernate HHH-17453으로 상위 클래스에서 전파되지 않아 본 클래스에 직접 선언한다(LT-03).
 */
@Entity
@Table(name = "review")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends AbstractPublicIdSoftDeletableEntity {

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private Long orderItemId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "buyer_id", nullable = false, updatable = false)
    private Long buyerId;

    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    /** 작성 시점 order_item.option_label 스냅샷(단순상품·V20 이전 주문은 NULL). 이후 교환으로 품목 옵션이 바뀌어도 그대로다. */
    @Column(name = "option_label", length = 500, updatable = false)
    private String optionLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReviewStatus status;

    /** 관리자 숨김 사유. HIDDEN일 때만 값이 있다(V40 chk_review_hidden_reason). */
    @Column(name = "hidden_reason", length = 200)
    private String hiddenReason;

    @Column(name = "helpful_count", nullable = false, insertable = false, updatable = false)
    private int helpfulCount;

    @ElementCollection
    @CollectionTable(name = "review_keyword_selection", joinColumns = @JoinColumn(name = "review_id"))
    @Column(name = "keyword_id", nullable = false)
    private Set<Long> keywordIds = new HashSet<>();

    /**
     * 공개 상태(VISIBLE)로 리뷰를 만든다. 자격(본인·구매확정·품목당 1개)과 키워드 소속은 서비스가 검증한다.
     *
     * @throws IllegalArgumentException 필수값 누락·별점 범위(1~5) 밖
     */
    public static Review create(Long orderItemId, Long productId, Long buyerId, int rating, String content, String optionLabel,
            Collection<Long> keywordIds) {
        if (orderItemId == null || productId == null || buyerId == null) {
            throw new IllegalArgumentException("Review 필수값 누락(orderItemId·productId·buyerId).");
        }
        Review review = new Review();
        review.orderItemId = orderItemId;
        review.productId = productId;
        review.buyerId = buyerId;
        review.optionLabel = optionLabel;
        review.status = ReviewStatus.VISIBLE;
        review.edit(rating, content, keywordIds);
        return review;
    }

    /**
     * 별점·본문·키워드를 통째로 바꾼다(작성자 수정·전체 교체). 사진 교체는 첨부 연결로 서비스가 한다.
     *
     * @throws IllegalArgumentException 별점 범위(1~5) 밖·본문 공백
     */
    public void edit(int rating, String content, Collection<Long> keywordIds) {
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw new IllegalArgumentException("별점은 " + MIN_RATING + "~" + MAX_RATING + "이어야 합니다: " + rating);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("리뷰 본문은 비어 있을 수 없습니다.");
        }
        this.rating = rating;
        this.content = content;
        this.keywordIds.clear();
        this.keywordIds.addAll(keywordIds);
    }

    /**
     * 관리자 숨김·숨김 해제. 숨기면 사유를 남기고, 해제하면 사유를 비운다.
     *
     * @throws IllegalStateException 같은 상태로의 재요청(호출부가 422로 변환)
     */
    public void changeStatus(ReviewStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("리뷰 상태를 전환할 수 없습니다: " + status + " → " + target);
        }
        this.status = target;
        this.hiddenReason = target == ReviewStatus.HIDDEN ? reason : null;
    }

    public boolean isWrittenBy(Long userId) {
        return buyerId.equals(userId);
    }

    @Override
    protected String getPublicIdPrefix() {
        return "rvw";
    }
}

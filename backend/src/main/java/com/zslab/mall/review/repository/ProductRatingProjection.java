package com.zslab.mall.review.repository;

/** 상품 목록 카드 별점(상품 id·공개 리뷰 수·평균 별점). */
public interface ProductRatingProjection {

    Long getProductId();

    Long getReviewCount();

    Double getAverageRating();
}

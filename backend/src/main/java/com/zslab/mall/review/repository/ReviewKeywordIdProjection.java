package com.zslab.mall.review.repository;

/** 목록 항목 키워드 배치 조회 한 행(리뷰 id·키워드 id). */
public interface ReviewKeywordIdProjection {

    Long getReviewId();

    Long getKeywordId();
}

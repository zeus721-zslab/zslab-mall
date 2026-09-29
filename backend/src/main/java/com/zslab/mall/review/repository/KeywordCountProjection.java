package com.zslab.mall.review.repository;

/** 키워드 집계 한 칸(키워드 id·선택한 공개 리뷰 수). */
public interface KeywordCountProjection {

    Long getKeywordId();

    Long getReviewCount();
}

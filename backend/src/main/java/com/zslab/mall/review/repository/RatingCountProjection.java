package com.zslab.mall.review.repository;

/** 별점 분포 한 칸(별점·공개 리뷰 수). */
public interface RatingCountProjection {

    Integer getRating();

    Long getReviewCount();
}

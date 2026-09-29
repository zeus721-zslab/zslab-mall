package com.zslab.mall.review.repository;

/** 최근 사진 한 장(서빙 URL·소속 리뷰 public_id). */
public interface ReviewPhotoProjection {

    String getFilePath();

    String getReviewPublicId();
}

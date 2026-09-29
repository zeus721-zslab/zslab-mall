package com.zslab.mall.review.repository;

/** 주문 응답 리뷰 상태용(품목 id → 리뷰 public_id·삭제된 리뷰는 null · 리뷰 상태 VISIBLE/HIDDEN). */
public interface ReviewByOrderItemProjection {

    Long getOrderItemId();

    String getReviewPublicId();

    String getReviewStatus();
}

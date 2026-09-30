package com.zslab.mall.demoseed.repository;

import java.time.LocalDateTime;

/** 데모 리뷰 후보 품목 한 행(D-245 · JPQL projection). */
public interface DemoReviewCandidateRow {
    Long getOrderItemId();

    String getOrderItemPublicId();

    Long getProductId();

    String getProductName();

    Long getCategoryId();

    Long getBuyerId();

    String getBuyerEmail();

    LocalDateTime getConfirmedAt();
}

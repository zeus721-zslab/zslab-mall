package com.zslab.mall.demoseed.repository;

/** 상품별 기존 리뷰 본문(D-245 · 같은 상품 안 중복 문구 방지용 projection). */
public interface DemoReviewContentRow {
    Long getProductId();

    String getContent();
}

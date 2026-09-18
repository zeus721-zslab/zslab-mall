package com.zslab.mall.product.repository;

/** 카테고리별 활성 상품 수(Track 89-C 관리자 카테고리 목록·삭제 가드). */
public interface CategoryProductCountProjection {

    Long getCategoryId();

    Long getProductCount();
}

package com.zslab.mall.product.controller.request;

/**
 * 셀러 상품 목록 정렬(Track 90-C-1). {@code AdminProductSort}와 값을 맞추되 셀러 계약은 독립 enum으로 둔다(관리자 enum 무수정·미사용).
 * LATEST(등록일 최신순)가 기본이다.
 */
public enum SellerProductSort {
    LATEST,
    NAME,
    PRICE_ASC,
    PRICE_DESC
}

package com.zslab.mall.product.controller.request;

/**
 * 관리자 상품 목록 정렬 기준(Track 76). 조회 파라미터라 4층위 enum 잠금 대상이 아니다({@link ProductCatalogSort} 정합).
 * PRICE는 base_price(판매가) 기준이다(카탈로그 대표가와 달리 variant 추가금 미반영·관리자 표는 기본 판매가 열을 정렬).
 */
public enum AdminProductSort {
    LATEST,
    NAME,
    PRICE_ASC,
    PRICE_DESC
}

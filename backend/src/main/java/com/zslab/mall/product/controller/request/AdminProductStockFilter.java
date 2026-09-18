package com.zslab.mall.product.controller.request;

/**
 * 관리자 상품 목록 재고 필터(Track 89-A). 조회 파라미터라 4층위 enum 잠금 대상이 아니다({@link AdminProductSort} 정합).
 * 미지정(null)이 전체다. 각 값의 판정식은 {@code AdminProductSpecifications.stockFilter} Javadoc 참조.
 */
public enum AdminProductStockFilter {
    /** 가용재고 1~5({@code LowStockThreshold})인 variant를 1개 이상 보유. */
    LOW,
    /** 가용재고 0인 variant를 1개 이상 보유. */
    OUT,
    /** 보유 variant 전부 가용재고 6 이상(임박·0 없음). */
    IN_STOCK
}

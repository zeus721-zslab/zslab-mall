package com.zslab.mall.stats.enums;

/**
 * 매출 분해 축(Track 87·D-181·요청 파라미터 전용). CATEGORY는 order_item에 스냅샷이 없어 product.category_id(현행)를 경유하므로
 * 상품 카테고리 변경 시 과거 주문의 귀속이 바뀐다(V32 스냅샷 이월). SELLER·PRODUCT는 order_item 스냅샷 기준이다.
 */
public enum StatsAxis {
    CATEGORY,
    SELLER,
    PRODUCT
}

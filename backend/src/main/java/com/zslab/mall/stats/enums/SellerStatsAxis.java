package com.zslab.mall.stats.enums;

/**
 * 셀러 매출 분해 축(Track 90-E-1·D-200·요청 파라미터 전용). 관리자 {@link StatsAxis}의 SELLER 축은 셀러에게 무의미하므로 별도 enum이며,
 * OPTION은 order_item.variant_id 그룹·option_label 스냅샷(주문 시점) 기준이다. CATEGORY는 관리자와 같이 product.category_id(현행)를
 * 경유하므로 상품 카테고리 변경 시 과거 주문의 귀속이 바뀐다(D-181).
 */
public enum SellerStatsAxis {
    PRODUCT,
    OPTION,
    CATEGORY
}

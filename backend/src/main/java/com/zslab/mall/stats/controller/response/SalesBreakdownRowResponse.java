package com.zslab.mall.stats.controller.response;

/**
 * 분해 테이블 1행(Track 87·D-181). key는 축 식별자(CATEGORY = categoryId 문자열·SELLER/PRODUCT = public_id·미존재면 null),
 * name은 표시명(PRODUCT는 order_item.product_name 스냅샷·SELLER/CATEGORY는 현행 행·미존재면 null → FE 대체 표기).
 * share는 totalRevenue 대비 비중(% 소수 2자리·전체 0이면 0), compareRevenue는 compare=NONE이거나 비교 기간에 그 키가 없으면 null(전역 NON_NULL·필드 생략),
 * drillable은 하위 상품 목록으로 내려갈 수 있는지(CATEGORY·SELLER 최상위 행만 true).
 */
public record SalesBreakdownRowResponse(
        String key,
        String name,
        long revenue,
        double share,
        long orderCount,
        long quantity,
        Long compareRevenue,
        boolean drillable) {
}

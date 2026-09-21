package com.zslab.mall.stats.controller.response;

/**
 * 셀러 매출 분해 테이블 1행(Track 90-E-1). key는 축 식별자(PRODUCT = 상품 public_id·OPTION = variant public_id·CATEGORY = categoryId 문자열·
 * 미존재(soft-delete)면 null), name은 표시명(PRODUCT = order_item.product_name 스냅샷·OPTION = "상품명 / 옵션라벨" 스냅샷·CATEGORY = 현행
 * 카테고리명·미존재면 null → FE 대체 표기). share는 셀러 기간 매출 대비 비중(% 소수 2자리·전체 0이면 0), compareRevenue는 compare=NONE이거나
 * 비교 기간에 그 키가 없으면 null. 관리자 행의 drillable은 없다(셀러는 드릴다운 미제공).
 */
public record SellerSalesBreakdownRowResponse(
        String key,
        String name,
        long revenue,
        double share,
        long orderCount,
        long quantity,
        Long compareRevenue) {
}

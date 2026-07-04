package com.zslab.mall.product.controller.response;

/**
 * 구매자 카탈로그 목록 요약 응답(Track 44·D1~D3). 목록 카드 렌더에 필요한 필드만 노출한다 — 내부 식별자(sellerId·상품
 * 내부 id)·원가(cost) 등은 노출하지 않는다(과잉 노출 금지).
 *
 * @param productPublicId 외부 노출 상품 식별자(prd_)
 * @param name 상품명
 * @param mainImageUrl 대표 이미지 URL(is_main 우선·없으면 null)
 * @param displayPrice 대표가 = basePrice + 판매가능 variant의 MIN(additional_price)(D3·"N원~" 최저값·표기는 프론트 위임)
 * @param soldOut 상품 단위 품절 여부(판매가능 variant가 모두 품절이거나 없으면 true·D2)
 * @param categoryId 카테고리 식별자(공개 taxonomy·필터 왕복용)
 * @param categoryName 카테고리 표시명
 * @param sellerName 판매자 상호명(company_name)
 */
public record ProductSummaryResponse(
        String productPublicId,
        String name,
        String mainImageUrl,
        long displayPrice,
        boolean soldOut,
        Long categoryId,
        String categoryName,
        String sellerName) {
}

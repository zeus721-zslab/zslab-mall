package com.zslab.mall.product.controller.response;

import java.util.List;

/**
 * 구매자 카탈로그 단건 상세 응답(Track 44·D1~D3). 요약 필드 + 옵션 그룹/값·이미지 목록·판매가능 variant 목록을 노출한다.
 * DEFAULT sentinel 옵션 그룹(단순상품 합성용 내부 옵션)은 optionGroups·variant options에서 제외한다(내부 유지·노출 금지).
 * variants는 판매가능(status=SALE·삭제제외) variant만 노출하며, 개별 soldOut은 재고·수동품절 기준이다.
 *
 * @param productPublicId 외부 노출 상품 식별자(prd_)
 * @param name 상품명
 * @param description 상품 상세 설명(nullable)
 * @param categoryId 카테고리 식별자
 * @param categoryName 카테고리 표시명
 * @param sellerName 판매자 상호명(company_name)
 * @param displayPrice 대표가(D3·요약과 동일 계산)
 * @param soldOut 상품 단위 품절 여부(D2)
 * @param images 이미지 목록(display_order 오름차순)
 * @param optionGroups 옵션 그룹 목록(DEFAULT sentinel 제외·display_order 오름차순)
 * @param variants 판매가능 variant 목록
 */
public record ProductDetailResponse(
        String productPublicId,
        String name,
        String description,
        Long categoryId,
        String categoryName,
        String sellerName,
        long displayPrice,
        boolean soldOut,
        List<Image> images,
        List<OptionGroup> optionGroups,
        List<Variant> variants) {

    /** 상품 이미지. */
    public record Image(String imageUrl, int displayOrder, boolean main) {
    }

    /** 옵션 그룹(예: 색상)과 그 값 목록. */
    public record OptionGroup(String name, int displayOrder, List<OptionValue> values) {
    }

    /** 옵션값(예: 검정). */
    public record OptionValue(String value, int displayOrder) {
    }

    /**
     * 판매 variant/SKU. salePrice = basePrice + additional_price. options는 해당 variant의 옵션 조합(DEFAULT 제외·단순상품은 빈 목록).
     */
    public record Variant(String variantPublicId, long salePrice, boolean soldOut, List<Option> options) {
    }

    /** variant 옵션 조합의 한 축(그룹명 + 선택값). */
    public record Option(String groupName, String value) {
    }
}

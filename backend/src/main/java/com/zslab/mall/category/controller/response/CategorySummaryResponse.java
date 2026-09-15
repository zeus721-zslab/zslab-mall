package com.zslab.mall.category.controller.response;

/**
 * 공개 카테고리 목록 항목 응답(Track 72). 식별자·필드명은 {@link CreateCategoryResponse}(categoryId·displayName·sortOrder)와
 * 동일 계약을 따른다. depth·parent는 루트만 반환하므로 노출하지 않는다.
 *
 * @param categoryId 카테고리 식별자(내부 Long·공개 taxonomy 식별자·ProductSummaryResponse.categoryId와 동일 값)
 * @param displayName 카테고리 표시명
 * @param sortOrder 정렬 순서(응답은 이 값 오름차순·동순위 id 오름차순)
 */
public record CategorySummaryResponse(
        Long categoryId,
        String displayName,
        int sortOrder) {
}

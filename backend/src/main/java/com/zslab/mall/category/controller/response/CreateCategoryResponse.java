package com.zslab.mall.category.controller.response;

/**
 * 루트 카테고리 생성 응답(Track 46). 식별자는 내부 Long id를 그대로 노출한다 — Track 44 {@code ProductSummaryResponse}가
 * 이미 categoryId(Long)를 공개 taxonomy 식별자로 노출하는 계약과 정합한다(category는 public_id 컬럼 부재·M4 확정).
 */
public record CreateCategoryResponse(
        Long categoryId,
        String displayName,
        int depth,
        int sortOrder) {
}

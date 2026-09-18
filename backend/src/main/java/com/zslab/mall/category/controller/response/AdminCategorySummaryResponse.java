package com.zslab.mall.category.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.category.entity.Category;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/**
 * 관리자 카테고리 목록 행(Track 89-C D-185). 식별자는 공개 목록({@link CategorySummaryResponse})과 같은 내부 Long이다.
 *
 * @param commissionRate basis-point(1000 = 10.00%)·미설정이면 null(응답 non_null 정책상 키 자체가 생략됨)
 * @param productCount   연결된 활성 상품 수(삭제 상품 제외·삭제 가능 판정 기준)
 */
public record AdminCategorySummaryResponse(
        Long categoryId,
        String displayName,
        int sortOrder,
        Integer commissionRate,
        long productCount,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime createdAt) {

    public static AdminCategorySummaryResponse from(Category category, long productCount) {
        return new AdminCategorySummaryResponse(category.getId(), category.getDisplayName(), category.getSortOrder(),
                category.getCommissionRate(), productCount, category.getCreatedAt());
    }
}

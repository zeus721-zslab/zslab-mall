package com.zslab.mall.category.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 루트 카테고리 생성 요청(Track 46·ADMIN). API는 루트 생성만 노출하므로 parent·depth는 서버가 고정한다.
 *
 * <p>형식 검증(@NotBlank·@Size·@PositiveOrZero)만 담당한다. 도메인 규칙(중복 방어)은 DB 제약(uk_category_dedup_key)이
 * 최종 방어선이다({@link com.zslab.mall.category.service.CategoryService}).
 */
public record CreateCategoryRequest(
        @NotBlank @Size(max = 200) String displayName, // SoT: Category.displayName @Column(length=200)
        @NotNull @PositiveOrZero Integer sortOrder) {
}

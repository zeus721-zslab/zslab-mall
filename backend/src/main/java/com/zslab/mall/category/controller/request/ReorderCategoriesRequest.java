package com.zslab.mall.category.controller.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 관리자 카테고리 일괄 정렬 변경 요청(Track 89-C D-185). 활성 루트 카테고리 전체 id를 원하는 순서대로 담는다 — 배열 index가 새 sortOrder다.
 * 누락·중복·미존재 id는 Service가 400으로 거부한다(부분 배열은 나머지 순서를 정의할 수 없어 허용하지 않는다).
 */
public record ReorderCategoriesRequest(
        @NotEmpty List<@NotNull Long> categoryIds) {
}

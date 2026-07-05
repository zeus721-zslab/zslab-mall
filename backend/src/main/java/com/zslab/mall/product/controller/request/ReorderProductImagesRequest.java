package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 셀러 상품 이미지 정렬 순서 변경 요청(Track 59 BL-6). imageIds는 대상 상품의 활성 이미지 전량을 재배치 순서대로 정확히
 * 열거해야 한다(누락·과잉·중복 시 400). 목록 순서가 곧 display_order 0..n-1이 된다. 집합 일치·소유권 검증은 Service가
 * 담당한다(형식은 비어있지 않음만 강제).
 */
public record ReorderProductImagesRequest(
        @NotEmpty List<Long> imageIds) {
}

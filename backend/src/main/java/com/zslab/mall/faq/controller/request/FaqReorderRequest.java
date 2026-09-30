package com.zslab.mall.faq.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * 관리자 FAQ 카테고리 안 일괄 정렬 요청(Track 106-3). 해당 카테고리의 FAQ 전체 id(숨김 포함)를 원하는 순서대로 담는다 — 배열 index가 새
 * sortOrder다. 누락·중복·다른 카테고리 id는 Service가 400으로 거부한다(부분 배열은 나머지 순서를 정의할 수 없다 · Category 선례).
 */
public record FaqReorderRequest(
        @NotBlank @Pattern(regexp = "^(ORDER_PAYMENT|DELIVERY|CLAIM|ACCOUNT|REVIEW_QUESTION)$") String category,
        @NotEmpty List<@NotNull Long> faqIds) {
}

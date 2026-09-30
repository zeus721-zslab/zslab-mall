package com.zslab.mall.productquestion.controller.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 상품 질문 수정 요청(Track 106-2·본문 전체 교체). 형식 위반 400 · 답변 완료·숨김 질문은 서비스가 422로 막는다.
 *
 * @param content 질문 본문(trim 후 5~500자 · {@link ProductQuestionContent#PATTERN})
 */
public record ProductQuestionUpdateRequest(
        @NotNull @Pattern(regexp = ProductQuestionContent.PATTERN) String content) {
}

package com.zslab.mall.productquestion.controller.request;

import com.zslab.mall.productquestion.entity.ProductQuestion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 셀러 답변 등록·수정 요청(Track 106-2·PUT 1개로 덮어쓰기). 형식 위반 400 · 다른 셀러 상품 404·숨김 질문 422는 서비스가 판정한다.
 *
 * @param content 답변 본문(1~1000자·공백만 불가)
 */
public record ProductQuestionAnswerRequest(
        @NotBlank @Size(max = ProductQuestion.MAX_ANSWER_LENGTH) String content) {
}

package com.zslab.mall.productquestion.controller.response;

import com.zslab.mall.productquestion.enums.ProductQuestionSuggestionType;

/**
 * 즉시 답 항목(Track 106-2). text는 표시 텍스트(길면 발췌 — QNA는 질문, REVIEW는 본문, PRODUCT는 설명 조각). answer는 QNA만, id는 QNA
 * (질문 public_id)·REVIEW(리뷰 public_id)만 있다(없으면 키 생략 — 전역 non_null).
 */
public record ProductQuestionSuggestionResponse(
        ProductQuestionSuggestionType type,
        String text,
        String answer,
        String id) {
}

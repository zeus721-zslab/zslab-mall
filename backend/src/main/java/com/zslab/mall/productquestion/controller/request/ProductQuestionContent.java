package com.zslab.mall.productquestion.controller.request;

import com.zslab.mall.productquestion.entity.ProductQuestion;

/**
 * 질문 본문 형식(Track 106-2 · trim 후 5~500자). 등록·수정 요청이 함께 쓴다.
 */
final class ProductQuestionContent {

    /**
     * 앞뒤 공백을 뺀 본문이 첫 글자·끝 글자(비공백) + 가운데 (최소-2)~(최대-2)자인지 — 곧 trim 후 길이가 최소~최대인지. {@code @Size}는 공백을
     * 세므로 "trim 후" 조건을 표현하지 못해 정규식으로 둔다.
     */
    static final String PATTERN = "^\\s*\\S[\\s\\S]{" + (ProductQuestion.MIN_CONTENT_LENGTH - 2) + ","
            + (ProductQuestion.MAX_CONTENT_LENGTH - 2) + "}\\S\\s*$";

    private ProductQuestionContent() {
    }
}

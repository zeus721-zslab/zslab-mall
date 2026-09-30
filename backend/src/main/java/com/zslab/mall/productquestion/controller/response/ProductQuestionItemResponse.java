package com.zslab.mall.productquestion.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 공개 질문 목록 항목(Track 106-2). 작성자 식별 정보는 싣지 않는다(리뷰 규칙). writtenByMe는 구매자 쿠키로 로그인한 조회에서만 값이 있다
 * (익명이면 키 생략 — 전역 non_null). 미답변이면 answerContent·answeredAt이 없다.
 */
public record ProductQuestionItemResponse(
        String questionId,
        String content,
        String answerContent,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime answeredAt,
        Boolean writtenByMe,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {
}

package com.zslab.mall.review.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import java.util.List;

/**
 * 공개 리뷰 목록 항목(Track 106-1). 작성자 식별 정보는 싣지 않는다. helpedByMe·writtenByMe는 구매자 쿠키로 로그인한 조회에서만 값이 있다
 * (익명이면 키 생략 — 전역 non_null). writtenByMe는 본인 리뷰의 도움됐어요 버튼을 미리 잠그는 용도다(PR2).
 */
public record ReviewItemResponse(
        String reviewId,
        int rating,
        String content,
        String optionLabel,
        List<KeywordResponse> keywords,
        List<ReviewPhotoResponse> photos,
        int helpfulCount,
        Boolean helpedByMe,
        Boolean writtenByMe,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {
}

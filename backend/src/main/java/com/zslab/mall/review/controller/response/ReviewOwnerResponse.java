package com.zslab.mall.review.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.review.enums.ReviewStatus;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 작성자 본인 리뷰 단건(Track 106-1·수정 화면 초기값). photos의 attachmentId는 수정 요청 attachmentIds로 그대로 다시 보낸다.
 * hiddenReason은 숨김일 때만 값이 있다(키 생략 — 전역 non_null). photosPublic이 false면 숨김이라 사진 URL이 공개 서빙되지 않는다
 * (서빙 규칙은 공개 리뷰 사진만 200 — 작성자 본인도 같다).
 */
public record ReviewOwnerResponse(
        String reviewId,
        int rating,
        String content,
        String optionLabel,
        List<KeywordResponse> keywords,
        List<Photo> photos,
        ReviewStatus status,
        String hiddenReason,
        boolean photosPublic,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {

    public record Photo(String attachmentId, String url, String thumbnailUrl) {
    }
}

package com.zslab.mall.review.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.review.enums.ReviewStatus;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;

/** 관리자 리뷰 목록 항목(Track 106-1·숨김 판단용). 상품이 삭제됐으면 상품 식별·이름은 null(키 생략). */
public record AdminReviewResponse(
        String reviewId,
        String productPublicId,
        String productName,
        int rating,
        String content,
        String optionLabel,
        ReviewStatus status,
        int helpfulCount,
        List<String> photoUrls,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {
}

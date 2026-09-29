package com.zslab.mall.review.controller.request;

import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.service.ReviewAttachmentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 리뷰 수정 요청(Track 106-1·전체 교체). 별점·키워드·본문·사진 목록을 통째로 바꾼다 — 목록에서 빠진 사진은 연결이 풀려 공개 서빙에서 사라지고
 * 정리 배치 대상이 된다. 이미 이 리뷰에 연결된 사진 id는 그대로 다시 보내면 된다(순서 = 표시 순서).
 */
public record ReviewUpdateRequest(
        @NotNull @Min(Review.MIN_RATING) @Max(Review.MAX_RATING) Integer rating,
        @Size(max = 10) List<@NotBlank @Size(max = 50) String> keywordCodes,
        @NotBlank @Size(max = 1000) String content,
        @Size(max = ReviewAttachmentService.MAX_PHOTOS_PER_REVIEW) List<@NotBlank @Size(max = 30) String> attachmentIds) {

    public List<String> keywordCodesOrEmpty() {
        return keywordCodes == null ? List.of() : keywordCodes;
    }

    public List<String> attachmentIdsOrEmpty() {
        return attachmentIds == null ? List.of() : attachmentIds;
    }
}

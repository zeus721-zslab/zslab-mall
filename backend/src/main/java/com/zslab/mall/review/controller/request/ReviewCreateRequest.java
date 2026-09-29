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
 * 리뷰 작성 요청(Track 106-1). 형식만 검증한다 — 자격(본인·구매확정·품목당 1개)·키워드 소속·사진 소유는 서비스가 판정한다. keywordCodes·
 * attachmentIds는 없으면 선택 없음. attachmentIds는 리뷰 사진 업로드 응답의 attachmentId(att_)이며 순서가 표시 순서다.
 */
public record ReviewCreateRequest(
        @NotBlank @Size(max = 30) String orderItemId,
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

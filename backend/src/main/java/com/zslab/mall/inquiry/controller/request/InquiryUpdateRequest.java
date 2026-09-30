package com.zslab.mall.inquiry.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 운영자 문의 수정 요청(Track 106-4·카테고리·본문 전체 교체). 첨부 주문은 바꾸지 않는다. 형식 위반 400 · 답변 완료 문의는 서비스가 422로 막는다.
 *
 * @param category 카테고리({@link InquiryContent#CATEGORY_PATTERN})
 * @param content  문의 본문(trim 후 5~500자 · {@link InquiryContent#PATTERN})
 */
public record InquiryUpdateRequest(
        @NotBlank @Pattern(regexp = InquiryContent.CATEGORY_PATTERN) String category,
        @NotNull @Pattern(regexp = InquiryContent.PATTERN) String content) {
}

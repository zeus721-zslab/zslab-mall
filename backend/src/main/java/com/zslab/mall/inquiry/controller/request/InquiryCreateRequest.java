package com.zslab.mall.inquiry.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 운영자 문의 등록 요청(Track 106-4). 형식만 검증한다(위반 400 VALIDATION_FAILED) — 주문 소유 판정은 서비스가 한다.
 *
 * @param category 카테고리({@link InquiryContent#CATEGORY_PATTERN})
 * @param content  문의 본문(trim 후 5~500자 · {@link InquiryContent#PATTERN})
 * @param orderId  첨부 주문 public_id(ord_ + ULID 26자 · 선택 · 없으면 null · 빈 문자열은 형식 위반 400)
 */
public record InquiryCreateRequest(
        @NotBlank @Pattern(regexp = InquiryContent.CATEGORY_PATTERN) String category,
        @NotNull @Pattern(regexp = InquiryContent.PATTERN) String content,
        @Pattern(regexp = "^ord_[0-9A-Z]{26}$") String orderId) {
}

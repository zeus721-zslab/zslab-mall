package com.zslab.mall.inquiry.controller.request;

import com.zslab.mall.inquiry.entity.Inquiry;

/**
 * 문의 입력 형식(Track 106-4). 등록·수정 요청이 함께 쓴다.
 */
final class InquiryContent {

    /**
     * 앞뒤 공백을 뺀 본문 길이가 최소~최대인지(106-2 상품 질문 본문 규칙과 같은 식). {@code @Size}는 공백을 세므로 "trim 후" 조건을 표현하지
     * 못해 정규식으로 둔다.
     */
    static final String PATTERN = "^\\s*\\S[\\s\\S]{" + (Inquiry.MIN_CONTENT_LENGTH - 2) + ","
            + (Inquiry.MAX_CONTENT_LENGTH - 2) + "}\\S\\s*$";

    /** 카테고리 — 4층위 enum 잠금의 DTO 층(InquiryCategory · V43 chk_inquiry_category와 같은 5종). */
    static final String CATEGORY_PATTERN = "^(ORDER_PAYMENT|DELIVERY|CLAIM|ACCOUNT|OTHER)$";

    private InquiryContent() {
    }
}

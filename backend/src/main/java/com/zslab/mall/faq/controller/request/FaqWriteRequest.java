package com.zslab.mall.faq.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 FAQ 등록·수정 요청(Track 106-3 · 수정은 PUT 전체 치환). category는 4층위 enum 잠금의 DTO 층(위반 400 VALIDATION_FAILED).
 * 정렬 위치는 받지 않는다 — 등록·카테고리 이동 시 서버가 해당 카테고리 끝에 두고, 순서 변경은 정렬 API만 한다.
 */
public record FaqWriteRequest(
        @NotBlank @Pattern(regexp = "^(ORDER_PAYMENT|DELIVERY|CLAIM|ACCOUNT|REVIEW_QUESTION)$") String category,
        @NotBlank @Size(max = 200) String question, // SoT: Faq.question @Column(length=200)
        @NotBlank @Size(max = 2000) String answer, // SoT: Faq.answer @Column(length=2000)
        @NotNull Boolean visible) {
}

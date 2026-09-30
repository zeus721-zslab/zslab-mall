package com.zslab.mall.productquestion.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 상품 질문 등록 요청(Track 106-2). 형식만 검증한다(위반 400 VALIDATION_FAILED) — 상품 노출 판정은 서비스가 한다. 질문은 항상 공개다.
 *
 * @param productId 대상 상품 public_id(prd_)
 * @param content   질문 본문(trim 후 5~500자 · {@link ProductQuestionContent#PATTERN})
 */
public record ProductQuestionCreateRequest(
        @NotBlank @Size(max = 30) String productId,
        @NotNull @Pattern(regexp = ProductQuestionContent.PATTERN) String content) {
}

package com.zslab.mall.productquestion.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 질문 숨김·숨김 해제 요청(Track 106-2·리뷰 숨김과 같은 형태). status는 4층위 enum 잠금의 DTO 층(위반 400 VALIDATION_FAILED)이고
 * 같은 상태 재요청은 도메인이 판정한다(422). reason은 필수(200자 이하)이며 감사 이력에 남는다.
 */
public record AdminProductQuestionStatusChangeRequest(
        @NotBlank @Pattern(regexp = "^(VISIBLE|HIDDEN)$") String status,
        @NotBlank @Size(max = 200) String reason) {
}

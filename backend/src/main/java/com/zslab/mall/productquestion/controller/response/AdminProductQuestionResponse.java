package com.zslab.mall.productquestion.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 관리자 질문 목록 항목(Track 106-2·숨김 판단용). 상품이 삭제됐으면 상품 식별·이름은 null(키 생략). hiddenReason은 숨김일 때만 값이 있다
 * (해제 시 비움 — V41 CHECK로 상태와 쌍). 미답변이면 answerContent·answeredAt이 없다.
 */
public record AdminProductQuestionResponse(
        String questionId,
        String productPublicId,
        String productName,
        String content,
        String answerContent,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime answeredAt,
        ProductQuestionStatus status,
        String hiddenReason,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {
}

package com.zslab.mall.productquestion.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 셀러 질문 목록 항목(Track 106-2·자기 상품의 공개 질문). 작성자 식별 정보는 싣지 않는다. 미답변이면 answerContent·answeredAt이 없다.
 */
public record SellerProductQuestionResponse(
        String questionId,
        String productPublicId,
        String productName,
        String content,
        String answerContent,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime answeredAt,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {
}

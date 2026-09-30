package com.zslab.mall.productquestion.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 내 질문 목록 항목(Track 106-2·숨김 포함). 숨김이면 사유를 함께 보여준다(리뷰 작성자 단건 선례). editable·deletable은 서버 상태 규칙
 * (수정 = 미답변 + VISIBLE · 삭제 = 미답변)의 결과라 화면이 규칙을 다시 계산하지 않는다. 상품이 삭제됐으면 상품 식별·이름은 null(키 생략).
 */
public record MyProductQuestionResponse(
        String questionId,
        String productPublicId,
        String productName,
        String content,
        ProductQuestionStatus status,
        String hiddenReason,
        String answerContent,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime answeredAt,
        boolean editable,
        boolean deletable,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {
}

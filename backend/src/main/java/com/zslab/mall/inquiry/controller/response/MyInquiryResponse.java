package com.zslab.mall.inquiry.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 내 문의 목록 항목(Track 106-4). editable·deletable(미답변일 때)·unread(답변이 있고 아직 확인하지 않음)는 서버 규칙의 결과라 화면이 다시
 * 계산하지 않는다. 주문을 첨부하지 않았으면 orderId·orderNo는 null(키 생략).
 */
public record MyInquiryResponse(
        String inquiryId,
        InquiryCategory category,
        String content,
        String orderId,
        String orderNo,
        String answerContent,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime answeredAt,
        boolean editable,
        boolean deletable,
        boolean unread,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {
}

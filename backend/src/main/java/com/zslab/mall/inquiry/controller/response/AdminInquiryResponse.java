package com.zslab.mall.inquiry.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 관리자 문의 목록 항목(Track 106-4). 작성자는 마스킹한 이메일만 싣는다(탈퇴 비식별화로 이메일이 없으면 null·키 생략). 주문을 첨부하지 않았으면
 * orderId·orderNo는 null. 미답변이면 answerContent·answeredAt이 없다.
 */
public record AdminInquiryResponse(
        String inquiryId,
        InquiryCategory category,
        String content,
        String orderId,
        String orderNo,
        String buyerEmailMasked,
        String answerContent,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime answeredAt,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime createdAt) {
}
